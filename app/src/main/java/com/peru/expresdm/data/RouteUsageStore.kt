package com.peru.expresdm.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decide qué rutas son favoritas a partir del uso, sin que el usuario marque nada.
 *
 * Lógica pura, sin Android, para poder probarla directamente.
 */
object FrequentRoutes {

    /** Veces que hay que consultar una ruta para que cuente como favorita. */
    const val MIN_USES = 2

    /** Más rutas que estas no aportan: dejan de ser "las de siempre". */
    const val MAX_ROUTES = 5

    /** Ids de las rutas favoritas, de la más usada a la menos. */
    fun rank(uses: Map<String, Int>): List<String> =
        uses.entries
            .filter { it.value >= MIN_USES }
            // A igual número de usos, el orden alfabético evita que la lista baile.
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_ROUTES)
            .map { it.key }
}

/**
 * Cuenta cuántas veces consulta el usuario cada ruta.
 *
 * Abrir el detalle de una ruta es la señal más clara de que se usa; las
 * favoritas salen solas de este registro con [FrequentRoutes]. Se guarda en el
 * teléfono, aparte para cada cuenta, y no sale de él.
 */
class RouteUsageStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val account = AccountPrefs(appContext, PREFS_NAME)
    private val prefs get() = account.current

    private val _uses = MutableStateFlow<Map<String, Int>>(emptyMap())
    val uses: StateFlow<Map<String, Int>> = _uses.asStateFlow()

    init {
        migrateManualFavorites()
        _uses.value = load()
        // Cada cuenta tiene sus favoritas.
        account.onAccountChanged { _uses.value = load() }
    }

    fun recordUse(routeId: String) {
        val count = (_uses.value[routeId] ?: 0) + 1
        prefs.edit().putInt(KEY_PREFIX + routeId, count).apply()
        _uses.value = _uses.value + (routeId to count)
    }

    private fun load(): Map<String, Int> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is Int) key.removePrefix(KEY_PREFIX) to value else null
        }.toMap()

    /**
     * Antes las favoritas se marcaban a mano con un corazón. Para no perderlas,
     * cada ruta marcada entra en el registro con los usos justos para seguir
     * siendo favorita, y el registro antiguo se borra.
     */
    private fun migrateManualFavorites() {
        val legacy = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val ids = legacy.getStringSet(LEGACY_KEY_IDS, null) ?: return

        val editor = prefs.edit()
        ids.forEach { id ->
            val current = prefs.getInt(KEY_PREFIX + id, 0)
            editor.putInt(KEY_PREFIX + id, maxOf(current, FrequentRoutes.MIN_USES))
        }
        editor.apply()
        legacy.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "expresdm_route_usage"
        private const val KEY_PREFIX = "uses_"

        private const val LEGACY_PREFS_NAME = "expresdm_favorites"
        private const val LEGACY_KEY_IDS = "favorite_route_ids"

        @Volatile
        private var instance: RouteUsageStore? = null

        fun get(context: Context): RouteUsageStore =
            instance ?: synchronized(this) {
                instance ?: RouteUsageStore(context).also { instance = it }
            }
    }
}
