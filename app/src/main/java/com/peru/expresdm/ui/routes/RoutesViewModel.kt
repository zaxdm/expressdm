package com.peru.expresdm.ui.routes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peru.expresdm.R
import com.peru.expresdm.data.AlertRepository
import com.peru.expresdm.data.AlertRules
import com.peru.expresdm.data.AppSettings
import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.GeoCoordinate
import com.peru.expresdm.data.Journey
import com.peru.expresdm.data.JourneyPlanner
import com.peru.expresdm.data.KnownPlaces
import com.peru.expresdm.data.NearbyRoute
import com.peru.expresdm.data.NearbyRoutes
import com.peru.expresdm.data.Place
import com.peru.expresdm.data.PlaceSearch
import com.peru.expresdm.data.PlaceSuggest
import com.peru.expresdm.data.RecentPlaceStore
import com.peru.expresdm.data.RoadAlert
import com.peru.expresdm.data.RouteRepository
import com.peru.expresdm.data.RouteSnapper
import com.peru.expresdm.data.RouteUsageStore
import com.peru.expresdm.data.Stop
import com.peru.expresdm.data.StreetPath
import com.peru.expresdm.data.StreetRouter
import com.peru.expresdm.data.UserPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ceil

/** Lo que la app sabe del acceso a la ubicación. */
enum class LocationAccess { UNKNOWN, NO_PERMISSION, SERVICES_OFF, READY }

/** Qué muestra la hoja del inicio. */
enum class HomeMode {
    /** Hay permiso, pero todavía no llegó la primera posición. */
    WAITING_LOCATION,

    /** El usuario no dio permiso de ubicación. */
    NO_PERMISSION,

    /** La ubicación del teléfono está apagada. */
    LOCATION_OFF,

    /** Rutas dentro del radio de búsqueda. */
    NEARBY,

    /** Ninguna ruta dentro del radio: se muestran igualmente las más próximas. */
    NONE_NEARBY,

    /** Consultando el buscador de lugares. */
    PLACE_SEARCHING,

    /** Lugares encontrados, para que el usuario elija uno. */
    PLACE_RESULTS,

    /** Micros que pasan por el lugar elegido. */
    PLACE_ROUTES,

    /** Cómo llegar al lugar elegido desde donde está el usuario. */
    PLACE_JOURNEYS
}

/** La búsqueda de lugares, paso a paso. */
sealed interface PlaceSearchState {
    data object Idle : PlaceSearchState
    data class Searching(val query: String) : PlaceSearchState
    data class Results(val query: String, val places: List<Place>, val offline: Boolean) : PlaceSearchState
    /** [origin] es de dónde se sale; `null` significa "desde donde estoy". */
    data class Chosen(val query: String, val place: Place, val origin: Place? = null) : PlaceSearchState
}

/** Lo que devuelve el buscador de lugares; [offline] si solo hay paradas conocidas. */
data class PlaceLookup(val places: List<Place>, val offline: Boolean)

/**
 * Ruta elegida en el mapa del inicio, lista para dibujar.
 *
 * Los caminos por calles llegan del servicio de rutas al cabo de un momento;
 * mientras tanto (o si no hay conexión) se usan líneas rectas.
 */
data class SelectedRoute(
    val route: BusRoute,
    /** Parada más cercana al usuario; `null` sin ubicación. */
    val boarding: NearbyRoute?,
    val routeLine: List<GeoCoordinate>,
    val followsStreets: Boolean,
    /** Camino a pie hasta la parada; `null` sin ubicación o si queda demasiado lejos. */
    val walkLine: List<GeoCoordinate>?,
    val walkMeters: Double?,
    val walkMinutes: Int?,
    val loadingStreets: Boolean,
    /** Alertas vigentes pegadas al recorrido. */
    val alertsOnRoute: Int
)

data class HomeState(
    val mode: HomeMode,
    val items: List<RouteListItem>,
    val places: List<PlaceResult> = emptyList(),
    /** Formas de llegar al lugar buscado, de la más rápida a la más lenta. */
    val journeys: List<Journey> = emptyList(),
    /** El viaje que el usuario tocó para verlo dibujado en el mapa. */
    val chosenJourney: Journey? = null,
    val search: PlaceSearchState = PlaceSearchState.Idle,
    val selected: SelectedRoute? = null,
    val alerts: List<RoadAlert> = emptyList(),
    val user: UserPosition? = null,
    /** Punto de salida elegido a mano en "¿A dónde vas?"; `null` si se sale desde donde está el usuario. */
    val origin: Place? = null
) {
    /** De dónde parte el viaje: el origen elegido o, si no hay, la posición del usuario. */
    val journeyStart: GeoCoordinate?
        get() = origin?.let { GeoCoordinate(it.latitude, it.longitude) }
            ?: user?.let { GeoCoordinate(it.latitude, it.longitude) }
}

/** Posición del mapa, para restaurarla al volver a la pestaña. */
data class MapCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val followingUser: Boolean
)

/**
 * Estado del inicio: rutas cercanas, búsqueda de lugares, ruta elegida y alertas.
 *
 * Sobrevive a rotaciones y a cambios de pestaña.
 */
class RoutesViewModel(app: Application) : AndroidViewModel(app) {

    /** Caminos de la ruta elegida, que llegan del servicio de rutas poco a poco. */
    private data class Selection(
        val routeId: String? = null,
        val busPath: StreetPath? = null,
        val loadingBus: Boolean = false,
        val walkPath: StreetPath? = null,
        val walkTarget: Stop? = null,
        /** Viaje completo elegido en "Cómo llegar"; excluye tener una ruta suelta abierta. */
        val journey: Journey? = null
    )

    private data class Environment(
        val here: UserPosition?,
        val access: LocationAccess,
        val routes: List<BusRoute>
    )

    private val usage = RouteUsageStore.get(app)
    private val recents = RecentPlaceStore.get(app)
    private val router = StreetRouter.get(app)
    private val position = MutableStateFlow<UserPosition?>(null)
    private val access = MutableStateFlow(LocationAccess.UNKNOWN)
    private val search = MutableStateFlow<PlaceSearchState>(PlaceSearchState.Idle)
    private val selection = MutableStateFlow(Selection())

    private var busJob: Job? = null
    private var walkJob: Job? = null
    private var searchJob: Job? = null

    init {
        // Al ajustarse a las calles una ruta grabada, sus paradas se mueven unos
        // metros: si es la elegida, el camino a pie hasta la parada se recalcula.
        viewModelScope.launch {
            RouteRepository.routes.collect { routes ->
                val current = selection.value
                val target = current.walkTarget ?: return@collect
                val route = routes.firstOrNull { it.id == current.routeId } ?: return@collect
                val here = position.value ?: return@collect
                if (target != NearbyRoutes.measure(route, here).boarding) refreshWalk()
            }
        }
    }

    var camera: MapCamera? = null
    var hasCenteredOnUser = false
    var hasAskedForLocation = false
    var locationPermanentlyDenied = false

    /** La burbuja del asistente se muestra con su nombre solo la primera vez. */
    var assistantIntroShown = false

    /** Cada minuto: las alertas caducan aunque nadie toque nada. */
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(ALERT_REFRESH_MS)
        }
    }

    private val activeAlerts: Flow<List<RoadAlert>> =
        combine(AlertRepository.alerts, clock, AppSettings.get(app).alertTypes) { alerts, now, types ->
            // Las propias se ven siempre: uno quiere saber que lo que publicó sigue ahí.
            AlertRules.active(alerts, now).filter { it.type in types || it.createdByMe }
        }

    private val environment: Flow<Environment> =
        combine(position, access, RouteRepository.routes) { here, locationAccess, routes ->
            Environment(here, locationAccess, routes)
        }

    val homeState: StateFlow<HomeState> =
        combine(environment, search, selection, activeAlerts) { env, searchState, sel, alerts ->
            buildState(env, searchState, sel, alerts)
        }
            // Medir el catálogo entero y buscar cómo llegar cuesta: se hace fuera
            // del hilo de la pantalla, y si llegan varios cambios seguidos solo
            // se calcula el último.
            .conflate()
            .flowOn(Dispatchers.Default)
            .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = buildState(
                Environment(null, LocationAccess.UNKNOWN, RouteRepository.all()),
                PlaceSearchState.Idle,
                Selection(),
                AlertRules.active(AlertRepository.alerts.value, System.currentTimeMillis())
            )
        )

    // --- Ubicación ---

    fun onLocationAccessChanged(value: LocationAccess) {
        access.value = value
    }

    fun onLocationChanged(latitude: Double, longitude: Double) {
        val next = UserPosition(latitude, longitude)
        val current = position.value
        // Recalcular con cada lectura del GPS haría saltar la lista y saturaría
        // el servicio de rutas: solo cuando el usuario se ha movido de verdad.
        // Al moverse de zona se piden al servidor las alertas de por aquí.
        if (current == null || NearbyRoutes.distanceMeters(current, next) >= ALERT_REFRESH_METERS) {
            viewModelScope.launch { AlertRepository.refresh(latitude, longitude) }
        }
        if (current == null || NearbyRoutes.distanceMeters(current, next) >= MIN_MOVEMENT_METERS) {
            position.value = next
            refreshWalk()
        }
    }

    // --- Búsqueda de lugares ---

    /**
     * Busca un lugar. Solo al pulsar "Buscar": las condiciones del buscador de
     * OpenStreetMap prohíben consultar mientras se escribe.
     */
    fun submitSearch(text: String) {
        val query = text.trim()
        if (query.length < MIN_QUERY_LENGTH) return

        clearJourney()
        clearSelection()
        searchJob?.cancel()
        search.value = PlaceSearchState.Searching(query)

        searchJob = viewModelScope.launch {
            val found = findPlaces(query)
            search.value = PlaceSearchState.Results(query, found.places, found.offline)
        }
    }

    /**
     * Paradas conocidas y lugares del mapa que coinciden con [text], del más
     * cercano al más lejano. Consulta el buscador de OpenStreetMap: solo debe
     * llamarse cuando el usuario pulsa "Buscar".
     */
    suspend fun findPlaces(text: String): PlaceLookup {
        val query = text.trim()
        // Las paradas conocidas responden al instante y sin conexión.
        val local = localPlaces(query)
        val remote = PlaceSearch.search(query)
        val reference = position.value ?: TRUJILLO_REFERENCE

        val extra = remote.orEmpty().filterNot { found ->
            local.any { known ->
                normalize(known.name) == normalize(found.name) &&
                    NearbyRoutes.distanceMeters(known.latitude, known.longitude, found.latitude, found.longitude) < SAME_PLACE_METERS
            }
        }
        // Los sitios conocidos (la UPAO, el Mall, un hospital) van primero y en
        // su orden: son lo que casi siempre se busca. Lo demás, de cerca a lejos.
        val known = knownPlaces(query)
        val rest = (local + extra).filterNot { sameAsAny(it, known) }.sortedBy {
            NearbyRoutes.distanceMeters(reference.latitude, reference.longitude, it.latitude, it.longitude)
        }
        return PlaceLookup(known + rest, offline = remote == null && known.isEmpty())
    }

    /**
     * Universidades, hospitales, mercados, centros comerciales y demás sitios
     * que vienen con la app. No sale del teléfono: sirve mientras se escribe y
     * sin conexión.
     */
    fun knownPlaces(text: String): List<Place> = KnownPlaces.search(text, position.value)

    private fun sameAsAny(place: Place, others: List<Place>): Boolean = others.any { other ->
        normalize(other.name) == normalize(place.name) &&
            NearbyRoutes.distanceMeters(other.latitude, other.longitude, place.latitude, place.longitude) < SAME_PLACE_METERS
    }

    /**
     * Lugares del mapa que coinciden con [text], para sugerirlos mientras se
     * escribe. Quien llama debe esperar a que el usuario pare de teclear.
     * Si el servicio de sugerencias no responde, se pregunta al buscador normal.
     */
    suspend fun suggestPlaces(text: String): PlaceLookup {
        val query = text.trim()
        if (query.length < MIN_QUERY_LENGTH) return PlaceLookup(emptyList(), offline = false)
        val known = knownPlaces(query)
        val found = PlaceSuggest.suggest(query, position.value) ?: PlaceSearch.search(query)
        val rest = found.orEmpty().filterNot { sameAsAny(it, known) }
        // Sin conexión, los sitios conocidos siguen saliendo.
        return PlaceLookup(known + rest, offline = found == null && known.isEmpty())
    }

    /**
     * Paradas cuyo nombre contiene [text]. No sale del teléfono, así que sí se
     * puede usar mientras se escribe.
     */
    fun suggestStops(text: String): List<Place> {
        val query = text.trim()
        if (query.length < MIN_QUERY_LENGTH) return emptyList()
        val reference = position.value ?: TRUJILLO_REFERENCE
        return localPlaces(query).sortedBy {
            NearbyRoutes.distanceMeters(reference.latitude, reference.longitude, it.latitude, it.longitude)
        }
    }

    /** Metros desde el usuario hasta [place]; `null` sin ubicación. */
    fun distanceTo(place: Place): Double? = position.value?.let {
        NearbyRoutes.distanceMeters(it.latitude, it.longitude, place.latitude, place.longitude)
    }

    /**
     * El lugar que mejor responde a [query], para ir a él sin elegirlo en una
     * lista: lo usan las órdenes de voz. `null` si no se encontró ninguno.
     */
    suspend fun bestPlace(query: String): Place? = findPlaces(query.trim()).places.firstOrNull()

    /** La posición del usuario como lugar, para usarla de origen o destino. */
    fun currentPlace(): Place? = position.value?.let {
        Place(getApplication<Application>().getString(R.string.my_location), "", it.latitude, it.longitude)
    }

    val recentPlaces: StateFlow<List<Place>> get() = recents.places

    /**
     * Calcula cómo llegar a [destination]. Con [origin] `null` se sale desde
     * donde está el usuario.
     */
    fun planTrip(origin: Place?, destination: Place) {
        searchJob?.cancel()
        clearJourney()
        clearSelection()
        recents.remember(destination)
        search.value = PlaceSearchState.Chosen(destination.name, destination, origin)
    }

    fun choosePlace(place: Place) {
        searchJob?.cancel()
        clearJourney()
        clearSelection()
        val query = when (val current = search.value) {
            is PlaceSearchState.Results -> current.query
            is PlaceSearchState.Chosen -> current.query
            is PlaceSearchState.Searching -> current.query
            PlaceSearchState.Idle -> place.name
        }
        search.value = PlaceSearchState.Chosen(query, place)
    }

    /** El destino funciona sin geocodificación ni conexión. */
    fun chooseDestination(latitude: Double, longitude: Double) {
        searchJob?.cancel()
        clearJourney()
        clearSelection()
        val label = getApplication<Application>().getString(R.string.destination_map)
        val place = Place(label, "", latitude, longitude)
        search.value = PlaceSearchState.Chosen(label, place)
    }

    fun clearSearch() {
        searchJob?.cancel()
        clearJourney()
        search.value = PlaceSearchState.Idle
    }

    private fun localPlaces(query: String): List<Place> {
        val wanted = normalize(query)
        return RouteRepository.all()
            .flatMap { route -> route.stops.map { stop -> stop to route } }
            .filter { (stop, _) -> normalize(stop.name).contains(wanted) }
            .groupBy { (stop, _) -> stop.name }
            .map { (name, pairs) ->
                val stop = pairs.first().first
                val routeNames = pairs.map { it.second.name }.distinct().joinToString(", ")
                Place(
                    name = name,
                    detail = getApplication<Application>().getString(R.string.search_stop_detail, routeNames),
                    latitude = stop.latitude,
                    longitude = stop.longitude
                )
            }
    }

    // --- Ruta elegida ---

    /** Traza la ruta en el mapa y calcula por dónde caminar hasta ella. */
    fun selectRoute(routeId: String) {
        val route = RouteRepository.byId(routeId) ?: return
        if (selection.value.routeId == routeId) return

        // Elegir una ruta cuenta como un uso: de ahí salen las favoritas.
        usage.recordUse(routeId)

        busJob?.cancel()
        walkJob?.cancel()
        // Una ruta grabada por un usuario ya trae su trazado real.
        val needsStreets = route.path == null
        selection.value = Selection(routeId = routeId, loadingBus = needsStreets)
        snapUserRouteIfNeeded(route)

        if (needsStreets) {
            busJob = viewModelScope.launch {
                val path = router.busPath(route)
                selection.update { if (it.routeId == routeId) it.copy(busPath = path, loadingBus = false) else it }
            }
        }
        refreshWalk()
    }

    /**
     * Las rutas grabadas con versiones anteriores, o guardadas sin conexión,
     * se ajustan a las calles la primera vez que se muestran.
     */
    private fun snapUserRouteIfNeeded(route: BusRoute) = RouteSnapper.snapRoute(router, route)

    fun clearSelection() {
        busJob?.cancel()
        walkJob?.cancel()
        if (selection.value.routeId != null) selection.value = Selection(journey = selection.value.journey)
    }

    // --- Cómo llegar ---

    /** Dibuja en el mapa el viaje completo, con sus tramos a pie y en micro. */
    fun chooseJourney(journey: Journey) {
        busJob?.cancel()
        walkJob?.cancel()
        // Cada micro que se usa cuenta como uso: de ahí salen las favoritas.
        journey.rides.forEach { usage.recordUse(it.route.id) }
        selection.value = Selection(journey = journey)
    }

    fun clearJourney() {
        if (selection.value.journey != null) selection.value = selection.value.copy(journey = null)
    }

    private fun refreshWalk() {
        val routeId = selection.value.routeId ?: return
        val route = RouteRepository.byId(routeId) ?: return
        val here = position.value ?: return
        val nearby = NearbyRoutes.measure(route, here)

        walkJob?.cancel()
        if (nearby.distanceMeters > NearbyRoutes.WALKABLE_METERS) return

        walkJob = viewModelScope.launch {
            val path = router.walkingPath(here, nearby.boarding)
            selection.update {
                if (it.routeId == routeId) it.copy(walkPath = path, walkTarget = nearby.boarding) else it
            }
        }
    }

    // --- Estado ---

    private fun buildState(
        env: Environment,
        searchState: PlaceSearchState,
        sel: Selection,
        alerts: List<RoadAlert>
    ): HomeState {
        val here = env.here
        val routes = env.routes
        val selected = sel.routeId
            ?.let { id -> routes.firstOrNull { it.id == id } }
            ?.let { buildSelected(it, here, sel, alerts) }

        fun itemFor(route: BusRoute) = RouteListItem(route, here?.let { NearbyRoutes.measure(route, it) })

        return when (searchState) {
            is PlaceSearchState.Searching -> HomeState(
                mode = HomeMode.PLACE_SEARCHING,
                items = emptyList(),
                search = searchState,
                selected = selected,
                alerts = alerts,
                user = here
            )

            is PlaceSearchState.Results -> HomeState(
                mode = HomeMode.PLACE_RESULTS,
                items = emptyList(),
                places = searchState.places.map { place ->
                    PlaceResult(
                        place,
                        here?.let { NearbyRoutes.distanceMeters(it.latitude, it.longitude, place.latitude, place.longitude) }
                    )
                },
                search = searchState,
                selected = selected,
                alerts = alerts,
                user = here
            )

            is PlaceSearchState.Chosen -> {
                val passing = NearbyRoutes
                    .passingNear(searchState.place.latitude, searchState.place.longitude, routes = routes)
                    .map { RouteListItem(it.route, it) }

                // Sabiendo de dónde se sale, lo útil es decir cómo llegar; sin
                // origen ni ubicación, al menos qué micros pasan por ahí.
                val start = searchState.origin?.let { UserPosition(it.latitude, it.longitude) } ?: here
                val journeys = start?.let {
                    plannedJourneys(it, GeoCoordinate(searchState.place.latitude, searchState.place.longitude), routes)
                }.orEmpty()

                HomeState(
                    mode = if (start != null) HomeMode.PLACE_JOURNEYS else HomeMode.PLACE_ROUTES,
                    items = passing,
                    journeys = journeys,
                    // Sin elegir ninguno se enseña el más rápido; el elegido se
                    // sigue por sus micros, porque sus metros cambian al moverse.
                    chosenJourney = sel.journey
                        ?.let { chosen -> journeys.firstOrNull { it.key == chosen.key } ?: chosen }
                        ?: journeys.firstOrNull(),
                    search = searchState,
                    selected = selected,
                    alerts = alerts,
                    user = here,
                    origin = searchState.origin
                )
            }

            PlaceSearchState.Idle -> if (here != null) {
                val nearby = NearbyRoutes.find(here, routes)
                HomeState(
                    mode = if (nearby.anyWithinRadius) HomeMode.NEARBY else HomeMode.NONE_NEARBY,
                    items = nearby.routes.map { RouteListItem(it.route, it) },
                    selected = selected,
                    alerts = alerts,
                    user = here
                )
            } else {
                // Sin posición todavía: todo el catálogo, y la hoja explica por qué.
                HomeState(
                    mode = when (env.access) {
                        LocationAccess.NO_PERMISSION -> HomeMode.NO_PERMISSION
                        LocationAccess.SERVICES_OFF -> HomeMode.LOCATION_OFF
                        LocationAccess.UNKNOWN, LocationAccess.READY -> HomeMode.WAITING_LOCATION
                    },
                    items = routes.map { itemFor(it) },
                    selected = selected,
                    alerts = alerts,
                    user = null
                )
            }
        }
    }

    private class PlannedJourneys(
        val origin: UserPosition,
        val destination: GeoCoordinate,
        val routes: List<BusRoute>,
        val journeys: List<Journey>
    )

    @Volatile
    private var lastPlan: PlannedJourneys? = null

    /**
     * Cómo llegar. La pantalla se recalcula con cada cambio (una alerta, la
     * ruta elegida, unos metros caminados): mientras no cambien el destino ni
     * las rutas y uno siga casi en el mismo sitio, vale el cálculo anterior.
     */
    private fun plannedJourneys(
        origin: UserPosition,
        destination: GeoCoordinate,
        routes: List<BusRoute>
    ): List<Journey> {
        lastPlan?.let { last ->
            val moved = NearbyRoutes.distanceMeters(
                last.origin.latitude, last.origin.longitude, origin.latitude, origin.longitude
            )
            if (last.routes === routes && last.destination == destination && moved < REPLAN_METERS) {
                return last.journeys
            }
        }
        return JourneyPlanner.plan(origin, destination, routes)
            .also { lastPlan = PlannedJourneys(origin, destination, routes, it) }
    }

    private fun buildSelected(
        route: BusRoute,
        here: UserPosition?,
        sel: Selection,
        alerts: List<RoadAlert>
    ): SelectedRoute {
        val boarding = here?.let { NearbyRoutes.measure(route, it) }
        val bus = sel.busPath
        val routeLine = route.path
            ?: bus?.points
            ?: route.stops.map { GeoCoordinate(it.latitude, it.longitude) }

        var walkLine: List<GeoCoordinate>? = null
        var walkMeters: Double? = null
        var walkMinutes: Int? = null

        if (here != null && boarding != null && boarding.distanceMeters <= NearbyRoutes.WALKABLE_METERS) {
            val userPoint = GeoCoordinate(here.latitude, here.longitude)
            val stopPoint = GeoCoordinate(boarding.boarding.latitude, boarding.boarding.longitude)
            val street = sel.walkPath?.takeIf { sel.walkTarget == boarding.boarding }

            if (street != null) {
                // El camino por calles empieza y acaba en la calle más próxima:
                // lo unimos al punto exacto del usuario y al de subida.
                walkLine = listOf(userPoint) + street.points + stopPoint
                walkMeters = street.meters
                walkMinutes = ceil(street.seconds / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1)
            } else {
                walkLine = listOf(userPoint, stopPoint)
                walkMeters = boarding.distanceMeters
                walkMinutes = boarding.walkingMinutes
            }
        }

        val alertsOnRoute = alerts.count { alert ->
            routeLine.any { point ->
                NearbyRoutes.distanceMeters(point.latitude, point.longitude, alert.latitude, alert.longitude) <=
                    ALERT_ON_ROUTE_METERS
            }
        }

        return SelectedRoute(
            route = route,
            boarding = boarding,
            routeLine = routeLine,
            followsStreets = route.path != null || bus != null,
            walkLine = walkLine,
            walkMeters = walkMeters,
            walkMinutes = walkMinutes,
            loadingStreets = sel.loadingBus,
            alertsOnRoute = alertsOnRoute
        )
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")

    private companion object {
        const val MIN_MOVEMENT_METERS = 25.0
        const val SECONDS_PER_MINUTE = 60.0
        const val MIN_QUERY_LENGTH = 2
        const val SAME_PLACE_METERS = 300.0
        const val ALERT_ON_ROUTE_METERS = 150.0
        const val ALERT_REFRESH_MS = 60_000L

        /** Caminar menos que esto no cambia cómo llegar: no se vuelve a calcular. */
        const val REPLAN_METERS = 25.0

        /** Con este movimiento ya conviene volver a preguntar por las alertas de la zona. */
        const val ALERT_REFRESH_METERS = 1_000.0
        val TRUJILLO_REFERENCE = UserPosition(-8.1120, -79.0288)
        val DIACRITICS = Regex("\\p{Mn}+")
    }
}
