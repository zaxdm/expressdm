package com.peru.expresdm.data

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Posición del usuario, sin depender de android.location para poder probarla. */
data class UserPosition(val latitude: Double, val longitude: Double)

/**
 * Una ruta junto con por dónde subir a ella y lo lejos que queda.
 *
 * El micro se para en cualquier punto de su recorrido, no solo en los puntos
 * de referencia: [boarding] es el punto del trazado más cercano al usuario, y
 * lleva el nombre de [nearestStop], la referencia conocida más próxima a él.
 */
data class NearbyRoute(
    val route: BusRoute,
    val nearestStop: Stop,
    val distanceMeters: Double,
    val boarding: Stop = nearestStop
) {
    /** Se sube justo en el punto de referencia, y no en otro sitio de la calle. */
    val boardsAtStop: Boolean
        get() = boarding == nearestStop

    /** Minutos a pie hasta esa parada, a paso normal. */
    val walkingMinutes: Int
        get() = ceil(distanceMeters / NearbyRoutes.WALKING_METERS_PER_MINUTE).toInt().coerceAtLeast(1)
}

/** Resultado de buscar rutas cercanas. */
data class NearbySearch(
    val routes: List<NearbyRoute>,
    /** `false` si ninguna ruta cae dentro del radio y se muestran las más próximas igualmente. */
    val anyWithinRadius: Boolean
)

/**
 * Encuentra las rutas que pasan cerca del usuario.
 *
 * Mide la distancia al trazado y no a los puntos de referencia: en Trujillo el
 * micro para donde se le pide, así que quien está en una calle por la que
 * pasa la ruta ya puede subir ahí mismo.
 *
 * Es lógica pura, sin Android, para poder probarla directamente.
 */
object NearbyRoutes {

    /** Hasta dónde consideramos que una ruta "pasa cerca": unos 20 minutos a pie. */
    const val RADIUS_METERS = 1_500.0

    /** Más rutas que estas no caben de un vistazo en la hoja del inicio. */
    const val MAX_RESULTS = 5

    /** Si nada cae dentro del radio, cuántas rutas mostramos igualmente. */
    const val FALLBACK_RESULTS = 3

    /** Más allá de esta distancia no tiene sentido hablar de "minutos a pie". */
    const val WALKABLE_METERS = 3_000.0

    /** Paso normal de un adulto. */
    const val WALKING_METERS_PER_MINUTE = 80.0

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Más cerca que esto del recorrido, el micro pasa por donde está el usuario. */
    const val ON_ROUTE_METERS = 40.0

    /** A esta distancia de un punto de referencia, se sube en él. */
    const val AT_STOP_METERS = 60.0

    /** El punto del recorrido de [route] más cercano a [position], y la referencia que tiene al lado. */
    fun measure(route: BusRoute, position: UserPosition): NearbyRoute {
        require(route.stops.isNotEmpty()) { "La ruta ${route.id} no tiene paradas" }

        val here = GeoCoordinate(position.latitude, position.longitude)
        val (point, meters) = DropOffPicker.nearestOnLine(here, DropOffPicker.lineOf(route))
        val nearest = route.stops.minBy { distanceMeters(point.latitude, point.longitude, it.latitude, it.longitude) }
        // Junto a la referencia se sube en ella, que es un sitio que la gente conoce.
        val atStop = distanceMeters(point.latitude, point.longitude, nearest.latitude, nearest.longitude) <= AT_STOP_METERS
        val boarding = if (atStop) nearest else Stop(nearest.name, point.latitude, point.longitude)
        return NearbyRoute(route, nearest, if (atStop) distanceMeters(position, nearest) else meters, boarding)
    }

    /** Rutas cercanas a [position], de la más próxima a la más lejana. */
    fun find(position: UserPosition, routes: List<BusRoute> = RouteRepository.all()): NearbySearch {
        val measured = routes
            .filter { it.stops.isNotEmpty() }
            .map { measure(it, position) }
            .sortedBy { it.distanceMeters }

        val inside = measured.filter { it.distanceMeters <= RADIUS_METERS }

        return if (inside.isNotEmpty()) {
            NearbySearch(inside.take(MAX_RESULTS), anyWithinRadius = true)
        } else {
            NearbySearch(measured.take(FALLBACK_RESULTS), anyWithinRadius = false)
        }
    }

    /** Radio para decir que un micro "pasa por" un lugar buscado. */
    const val PLACE_RADIUS_METERS = 600.0

    /**
     * Rutas que pasan cerca de un lugar: algún punto de su recorrido a menos
     * de [radiusMeters]. La distancia que se devuelve es a ese punto.
     */
    fun passingNear(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = PLACE_RADIUS_METERS,
        routes: List<BusRoute> = RouteRepository.all()
    ): List<NearbyRoute> {
        val place = UserPosition(latitude, longitude)
        return routes
            .filter { it.stops.isNotEmpty() }
            .map { measure(it, place) }
            .filter { it.distanceMeters <= radiusMeters }
            .sortedBy { it.distanceMeters }
    }

    fun distanceMeters(from: UserPosition, to: UserPosition): Double =
        distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)

    fun distanceMeters(from: UserPosition, to: Stop): Double =
        distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)

    /** Distancia sobre la superficie terrestre, con la fórmula del haversine. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val a = sinLat * sinLat +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinLon * sinLon
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
