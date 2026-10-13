package com.peru.expresdm.data

import android.content.Context
import com.peru.expresdm.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Qué destinos se guardan como recientes y en qué orden.
 *
 * Lógica pura, sin Android, para poder probarla directamente.
 */
object RecentPlaces {

    /** Más que estos ya no caben a la vista sin desplazar. */
    const val MAX_PLACES = 5

    /** Dos lugares a menos de esta distancia y con el mismo nombre son el mismo. */
    private const val SAME_PLACE_METERS = 60.0

    /** [place] pasa al principio; si ya estaba, no se repite. */
    fun add(current: List<Place>, place: Place): List<Place> =
        (listOf(place) + current.filterNot { isSame(it, place) }).take(MAX_PLACES)

    private fun isSame(a: Place, b: Place): Boolean =
        a.name.equals(b.name, ignoreCase = true) &&
            NearbyRoutes.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude) < SAME_PLACE_METERS
}

/**
 * Últimos destinos buscados, para volver a ellos con un toque.
 *
 * Se guardan en el teléfono y no salen de él.
 */
class RecentPlaceStore private constructor(context: Context) {

    private val account = AccountPrefs(context, PREFS_NAME)
    private val prefs get() = account.current

    /** Un punto tocado en el mapa o "mi ubicación" no tienen nombre propio: no sirven como recientes. */
    private val genericNames: Set<String> = context.applicationContext.run {
        setOf(getString(R.string.destination_map), getString(R.string.my_location))
    }

    private val _places = MutableStateFlow(load())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    init {
        // Cada cuenta tiene sus búsquedas.
        account.onAccountChanged { _places.value = load() }
    }

    fun remember(place: Place) {
        if (place.name.isBlank() || place.name in genericNames) return
        val next = RecentPlaces.add(_places.value, place)
        _places.value = next
        prefs.edit().putString(KEY_PLACES, encode(next)).apply()
    }

    fun clear() {
        _places.value = emptyList()
        prefs.edit().remove(KEY_PLACES).apply()
    }

    private fun load(): List<Place> {
        val raw = prefs.getString(KEY_PLACES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Place(
                    name = item.getString("name"),
                    detail = item.optString("detail"),
                    latitude = item.getDouble("latitude"),
                    longitude = item.getDouble("longitude")
                )
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun encode(places: List<Place>): String = JSONArray().apply {
        places.forEach { place ->
            put(
                JSONObject()
                    .put("name", place.name)
                    .put("detail", place.detail)
                    .put("latitude", place.latitude)
                    .put("longitude", place.longitude)
            )
        }
    }.toString()

    companion object {
        private const val PREFS_NAME = "recent_places"
        private const val KEY_PLACES = "places"

        @Volatile
        private var instance: RecentPlaceStore? = null

        fun get(context: Context): RecentPlaceStore =
            instance ?: synchronized(this) {
                instance ?: RecentPlaceStore(context).also { instance = it }
            }
    }
}
