package com.peru.expresdm.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint

/**
 * Rutas guardadas para verlas sin conexión.
 *
 * El recorrido y los datos de todas las rutas ya quedan en el teléfono con el
 * catálogo; lo que falta sin internet es el mapa de debajo. Guardar una ruta
 * descarga las teselas por donde pasa, y solo esas, cuando quien sirve el mapa
 * lo permite. OpenStreetMap no deja descargar por adelantado: con su mapa la
 * ruta se guarda igual, y sin conexión se ve la parte que ya se había mirado.
 *
 * Cuántas se pueden guardar depende de la cuenta: ver [Premium].
 */
class SavedRoutes private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val account = AccountPrefs(appContext, PREFS_NAME)
    private val prefs get() = account.current

    private val _ids = MutableStateFlow(load())

    init {
        // Cada cuenta tiene sus rutas guardadas.
        account.onAccountChanged { _ids.value = load() }
    }

    /** Ids de las rutas guardadas. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun isSaved(routeId: String): Boolean = routeId in _ids.value

    /** Cuántas puede guardar la cuenta que está usando la app. */
    val limit: Int get() = Premium.savedRoutesLimit()

    /**
     * Queda sitio para guardar otra. Quien tenía más guardadas que su tope (dejó
     * de ser Premium) las conserva, pero no puede añadir.
     */
    val hasRoom: Boolean get() = _ids.value.size < limit

    /**
     * Guarda la ruta y descarga su mapa. [onDone] dice si el mapa llegó entero;
     * la ruta queda guardada aunque falle, para poder reintentarlo.
     */
    fun save(route: BusRoute, onDone: (mapDownloaded: Boolean) -> Unit) {
        if (!isSaved(route.id) && !hasRoom) return
        update(_ids.value + route.id)

        val points = ArrayList(
            (route.path?.map { GeoPoint(it.latitude, it.longitude) }
                ?: route.stops.map { GeoPoint(it.latitude, it.longitude) })
        )
        if (points.size < 2) {
            onDone(false)
            return
        }

        // osmdroid lo comprueba en otro hilo y, si no se puede, cierra la app.
        val source = TileSourceFactory.MAPNIK
        if (!source.tileSourcePolicy.acceptsBulkDownload()) {
            onDone(false)
            return
        }

        try {
            val manager = CacheManager(source, SqlTileWriter(), MIN_ZOOM, MAX_ZOOM)
            manager.downloadAreaAsync(
                appContext, points, MIN_ZOOM, MAX_ZOOM,
                object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() = onDone(true)
                    override fun onTaskFailed(errors: Int) = onDone(false)
                    override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) = Unit
                    override fun downloadStarted() = Unit
                    override fun setPossibleTilesInArea(total: Int) = Unit
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo descargar el mapa de la ruta", e)
            onDone(false)
        }
    }

    /** La quita de las guardadas. Las teselas se quedan en la caché hasta que el sistema las limpie. */
    fun remove(routeId: String) = update(_ids.value - routeId)

    private fun load(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()

    private fun update(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_IDS, ids).apply()
        _ids.value = ids
    }

    companion object {
        private const val TAG = "SavedRoutes"
        private const val PREFS_NAME = "expresdm_saved_routes"
        private const val KEY_IDS = "ids"

        /**
         * Tope con Premium. No es mayor porque OpenStreetMap no permite bajarse
         * la ciudad entera: son los mapas de las rutas de uno, no un atlas.
         */
        const val MAX_SAVED = 20

        /** De ver el barrio a ver la calle. Más cerca serían demasiadas teselas. */
        private const val MIN_ZOOM = 13
        private const val MAX_ZOOM = 16

        @Volatile
        private var instance: SavedRoutes? = null

        fun get(context: Context): SavedRoutes =
            instance ?: synchronized(this) {
                instance ?: SavedRoutes(context).also { instance = it }
            }
    }
}
