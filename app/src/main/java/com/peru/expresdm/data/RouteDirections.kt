package com.peru.expresdm.data

/**
 * La ida y la vuelta de una línea.
 *
 * No hay un campo que diga el sentido: cada sentido es una ruta aparte, con el
 * mismo nombre y letra, y el origen y el destino cambiados. El servidor deja como
 * mucho una ida y una vuelta por línea. Como en la web, la primera del catálogo
 * es la ida, y la otra, la vuelta.
 *
 * Es lógica pura, sin Android, para poder probarla directamente.
 */
object RouteDirections {

    enum class Way { IDA, VUELTA }

    /** El sentido de una ruta y la ruta que hace el camino de regreso. */
    data class Directions(val way: Way, val partner: BusRoute)

    /** Un nombre sin mayúsculas, espacios de más ni adornos («C2♡» es «c2»). */
    fun plain(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Como [plain], pero el «♡» cuenta: en Trujillo la letra «con corazón» es
     * un ramal oficial propio (C1♡ no es la vuelta de C1).
     */
    private fun lineText(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}♡]+"), " ").trim()

    /** Empresa y letra: las rutas con la misma clave son una sola línea. */
    fun lineKey(route: BusRoute): String = "${lineText(route.name)}|${lineText(route.letter)}"

    /**
     * El sentido de [route] y su pareja en [routes], o `null` si la línea no
     * tiene otro recorrido.
     */
    fun of(route: BusRoute, routes: List<BusRoute>): Directions? {
        val key = lineKey(route)
        val line = routes.filter { lineKey(it) == key }
        val others = line.filter { it.id != route.id && it.stops.isNotEmpty() }
        if (others.isEmpty()) return null
        // Su pareja es la que sale de donde esta llega: primero por los
        // nombres, que es lo que lee la gente; si no, por el mapa.
        val partner = others.minBy { gap(route, it) }
        val first = line.first()
        val way = when {
            first.id == route.id -> Way.IDA
            first.id == partner.id -> Way.VUELTA
            // Con más de dos recorridos, la que va como la primera también es ida.
            else -> if (gap(first, route) < gap(route, first)) Way.VUELTA else Way.IDA
        }
        return Directions(way, partner)
    }

    /** Lo que hay que andar del final de [from] al principio de [to]; cero si los nombres coinciden. */
    private fun gap(from: BusRoute, to: BusRoute): Double {
        val destination = plain(from.destination)
        if (destination.isNotEmpty() && destination == plain(to.origin)) return 0.0
        val end = from.path?.lastOrNull()?.let { it.latitude to it.longitude }
            ?: from.stops.lastOrNull()?.let { it.latitude to it.longitude }
            ?: return Double.MAX_VALUE
        val start = to.path?.firstOrNull()?.let { it.latitude to it.longitude }
            ?: to.stops.firstOrNull()?.let { it.latitude to it.longitude }
            ?: return Double.MAX_VALUE
        // Un metro de más para que el nombre igual gane siempre.
        return 1.0 + NearbyRoutes.distanceMeters(end.first, end.second, start.first, start.second)
    }
}
