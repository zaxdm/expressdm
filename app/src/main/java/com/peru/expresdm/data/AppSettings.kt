package com.peru.expresdm.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Modos de tema que el usuario puede elegir. */
enum class ThemeMode(val storageKey: String) {
    /** Sigue el ajuste de Android. */
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** Constante equivalente de AppCompat. */
    val nightMode: Int
        get() = when (this) {
            SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

/** Idiomas disponibles en la app. */
enum class AppLanguage(val tag: String) {
    /** Usa el idioma del teléfono. */
    SYSTEM(""),
    SPANISH("es"),
    ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/**
 * Preferencias de apariencia, idioma y alertas.
 *
 * El tema se aplica con [AppCompatDelegate.setDefaultNightMode] y el idioma con
 * [AppCompatDelegate.setApplicationLocales], que es la API que Android usa
 * también para el ajuste de idioma por app en los Ajustes del sistema: en
 * Android 13 y superiores queda sincronizado con él.
 */
class AppSettings private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME, null))
        set(value) {
            prefs.edit().putString(KEY_THEME, value.storageKey).apply()
            applyTheme(value)
        }

    var language: AppLanguage
        get() = AppLanguage.fromTag(prefs.getString(KEY_LANGUAGE, null))
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value.tag).apply()
            applyLanguage(value)
        }

    /** A cuántos metros de la parada salta el aviso de bajada. */
    var arrivalDistanceMeters: Int
        get() = prefs.getInt(KEY_ARRIVAL_DISTANCE, DEFAULT_ARRIVAL_DISTANCE)
            .takeIf { it in ARRIVAL_DISTANCES } ?: DEFAULT_ARRIVAL_DISTANCE
        set(value) = prefs.edit().putInt(KEY_ARRIVAL_DISTANCE, value).apply()

    var arrivalVibration: Boolean
        get() = prefs.getBoolean(KEY_ARRIVAL_VIBRATION, true)
        set(value) = prefs.edit().putBoolean(KEY_ARRIVAL_VIBRATION, value).apply()

    var arrivalSound: Boolean
        get() = prefs.getBoolean(KEY_ARRIVAL_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_ARRIVAL_SOUND, value).apply()

    /**
     * Con qué suena el aviso: la clave de un [ArrivalTone] o la dirección de
     * uno de los [customSounds].
     */
    var arrivalSoundChoice: String
        get() = prefs.getString(KEY_ARRIVAL_SOUND_CHOICE, null) ?: ArrivalTone.DEFAULT.key
        set(value) = prefs.edit().putString(KEY_ARRIVAL_SOUND_CHOICE, value).apply()

    /** Sonidos que el usuario añadió desde su teléfono. */
    var customSounds: List<CustomSound>
        get() = try {
            val array = JSONArray(prefs.getString(KEY_CUSTOM_SOUNDS, "[]"))
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                CustomSound(item.getString("uri"), item.getString("name"))
            }
        } catch (e: JSONException) {
            emptyList()
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
            prefs.edit().putString(KEY_CUSTOM_SOUNDS, array.toString()).apply()
        }

    /** Avisar también unas cuadras antes de la parada. Solo tiene efecto con Premium. */
    var arrivalEarlyWarning: Boolean
        get() = prefs.getBoolean(KEY_ARRIVAL_EARLY, true)
        set(value) = prefs.edit().putBoolean(KEY_ARRIVAL_EARLY, value).apply()

    // --- Aviso por SMS al llegar (Premium) ---

    /** Mandar un SMS a [arrivalSmsContact] al llegar a la parada. Solo tiene efecto con Premium. */
    var arrivalSms: Boolean
        get() = prefs.getBoolean(KEY_ARRIVAL_SMS, false)
        set(value) = prefs.edit().putBoolean(KEY_ARRIVAL_SMS, value).apply()

    /** Mandarlo solo cuando el teléfono no tiene internet, que es para lo que sirve; si no, siempre. */
    var arrivalSmsOnlyOffline: Boolean
        get() = prefs.getBoolean(KEY_ARRIVAL_SMS_OFFLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_ARRIVAL_SMS_OFFLINE, value).apply()

    /** A quién se le avisa; `null` si todavía no eligió a nadie. */
    var arrivalSmsContact: SmsContact?
        get() {
            val number = prefs.getString(KEY_ARRIVAL_SMS_NUMBER, null)?.takeIf { it.isNotBlank() } ?: return null
            return SmsContact(prefs.getString(KEY_ARRIVAL_SMS_NAME, null).orEmpty(), number)
        }
        set(value) = prefs.edit()
            .putString(KEY_ARRIVAL_SMS_NAME, value?.name)
            .putString(KEY_ARRIVAL_SMS_NUMBER, value?.number)
            .apply()

    // --- Voz (Premium): solo tienen efecto con la cuenta de pago ---

    /** Leer en alto todas las respuestas del asistente, no solo las de preguntas habladas. */
    var voiceAnswers: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ANSWERS, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_ANSWERS, value).apply()

    /** Guía hablada del viaje: dónde subir, cuánto falta, dónde bajar y el transbordo. */
    var voiceGuide: Boolean
        get() = prefs.getBoolean(KEY_VOICE_GUIDE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_GUIDE, value).apply()

    /** Con el mapa a la vista, escuchar hasta oír «Subo» y abrir el asistente. */
    var voiceWake: Boolean
        get() = prefs.getBoolean(KEY_VOICE_WAKE, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_WAKE, value).apply()

    /** Avisar con una notificación cuando alguien reporta algo cerca o en una ruta que el usuario usa. */
    var alertNotifications: Boolean
        get() = prefs.getBoolean(KEY_ALERT_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_ALERT_NOTIFICATIONS, value).apply()

    /** Leer en voz alta el aviso de bajada, para quien no puede mirar la pantalla. */
    var arrivalVoice: Boolean
        get() = prefs.getBoolean(KEY_ARRIVAL_VOICE, false)
        set(value) = prefs.edit().putBoolean(KEY_ARRIVAL_VOICE, value).apply()

    private val _alertTypes = MutableStateFlow(readAlertTypes())

    /** Tipos de alerta que el usuario quiere ver, en el mapa y en la lista. */
    val alertTypes: StateFlow<Set<AlertType>> = _alertTypes.asStateFlow()

    fun setAlertTypeEnabled(type: AlertType, enabled: Boolean) {
        val types = if (enabled) _alertTypes.value + type else _alertTypes.value - type
        // Se guardan los apagados: un tipo que se añada más adelante nace encendido.
        prefs.edit()
            .putStringSet(KEY_ALERT_TYPES_OFF, (AlertType.entries - types).map { it.name }.toSet())
            .apply()
        _alertTypes.value = types
    }

    private fun readAlertTypes(): Set<AlertType> {
        val off = prefs.getStringSet(KEY_ALERT_TYPES_OFF, emptySet()).orEmpty()
        return AlertType.entries.filterNot { it.name in off }.toSet()
    }

    /**
     * Aplica el tema guardado. Se llama al arrancar, antes de que se cree
     * ninguna pantalla, para que la app abra ya con el aspecto correcto.
     */
    fun applySavedTheme() = applyTheme(themeMode)

    private fun applyTheme(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    private fun applyLanguage(language: AppLanguage) {
        // Una lista vacía devuelve el control al idioma del sistema.
        AppCompatDelegate.setApplicationLocales(
            if (language == AppLanguage.SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
        )
    }

    companion object {
        private const val PREFS_NAME = "expresdm_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_ARRIVAL_DISTANCE = "arrival_distance"
        private const val KEY_ARRIVAL_VIBRATION = "arrival_vibration"
        private const val KEY_ARRIVAL_SOUND = "arrival_sound"
        private const val KEY_ALERT_TYPES_OFF = "alert_types_off"
        private const val KEY_ARRIVAL_SOUND_CHOICE = "arrival_sound_choice"
        private const val KEY_CUSTOM_SOUNDS = "custom_sounds"
        private const val KEY_ALERT_NOTIFICATIONS = "alert_notifications"
        private const val KEY_ARRIVAL_VOICE = "arrival_voice"
        private const val KEY_ARRIVAL_EARLY = "arrival_early"
        private const val KEY_VOICE_ANSWERS = "voice_answers"
        private const val KEY_VOICE_GUIDE = "voice_guide"
        private const val KEY_VOICE_WAKE = "voice_wake"
        private const val KEY_ARRIVAL_SMS = "arrival_sms"
        private const val KEY_ARRIVAL_SMS_OFFLINE = "arrival_sms_offline"
        private const val KEY_ARRIVAL_SMS_NAME = "arrival_sms_name"
        private const val KEY_ARRIVAL_SMS_NUMBER = "arrival_sms_number"

        /** Más que esto ya no es una lista para elegir de un vistazo. */
        const val MAX_CUSTOM_SOUNDS = 10

        /** Anticipaciones que ofrece Ajustes, en metros. */
        val ARRIVAL_DISTANCES = listOf(100, 200, 500)
        const val DEFAULT_ARRIVAL_DISTANCE = 200

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
    }
}
