package com.peru.expresdm.data

/**
 * Lo que el usuario lleva hecho en la app, para el resumen del perfil.
 *
 * Las alertas son todas las que ha publicado, no las que siguen vigentes: de
 * esas casi nunca hay más de una. Las rutas son las que hay ahora: una borrada
 * del catálogo deja de sumar. Lógica pura, sin Android, para poder probarla
 * directamente.
 */
data class ProfileStats(
    /** Rutas que tiene guardadas para ver sin conexión. */
    val savedRoutes: Int,
    /** Rutas que grabó en este teléfono; solo se enseña a quien puede grabar. */
    val recordedRoutes: Int,
    /** Alertas que ha publicado en total. */
    val publishedAlerts: Int,
    /** Rutas distintas que ha consultado alguna vez. */
    val usedRoutes: Int
) {
    val isEmpty: Boolean
        get() = savedRoutes == 0 && recordedRoutes == 0 && publishedAlerts == 0 && usedRoutes == 0

    companion object {
        val EMPTY = ProfileStats(0, 0, 0, 0)

        fun of(
            routes: List<BusRoute>,
            savedIds: Set<String>,
            publishedAlerts: Int,
            uses: Map<String, Int>
        ): ProfileStats {
            val knownIds = routes.map { it.id }.toSet()
            return ProfileStats(
                savedRoutes = savedIds.count { it in knownIds },
                recordedRoutes = routes.count { it.createdByUser },
                publishedAlerts = publishedAlerts.coerceAtLeast(0),
                // Una ruta que ya no está en el catálogo no debería seguir
                // contando como "usada": el número quedaría sin nada detrás.
                usedRoutes = uses.count { (id, count) -> count > 0 && id in knownIds }
            )
        }
    }
}
