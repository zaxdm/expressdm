package com.peru.expresdm.data.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Quién está usando la app, cuando ha iniciado sesión. */
data class Session(
    val token: String,
    val userId: String,
    val email: String,
    val displayName: String,
    /**
     * Puede grabar rutas nuevas: es administrador o colaborador. Lo dice el
     * servidor al entrar. Solo sirve para enseñar u ocultar opciones; quien
     * manda de verdad es el servidor, que vuelve a comprobarlo en cada petición.
     */
    val isAdmin: Boolean = false,
    /** Foto de perfil guardada en el servidor, para verla en cualquier teléfono. */
    val avatarUrl: String? = null,
    /** Hasta cuándo es Premium la cuenta, en milisegundos; `null` si es gratuita. */
    val premiumUntil: Long? = null,
    /** Alertas que la cuenta ha publicado en total, vigentes o ya caducadas. */
    val alertsPublished: Int = 0
)

/**
 * Guarda la sesión en el teléfono para no pedir la contraseña en cada arranque.
 *
 * El token es la llave de la cuenta: vive en el almacenamiento privado de la
 * app, al que no llegan otras aplicaciones.
 */
class SessionStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(read())

    /** Sesión actual; `null` si nadie ha iniciado sesión. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    val token: String? get() = _session.value?.token

    private val userListeners = mutableListOf<(String?) -> Unit>()

    /** Avisa cuando entra otra cuenta o se cierra la sesión, con el id de quien queda. */
    fun onUserChanged(listener: (userId: String?) -> Unit) {
        userListeners += listener
    }

    fun save(session: Session) {
        val previous = _session.value?.userId
        prefs.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_NAME, session.displayName)
            .putBoolean(KEY_ADMIN, session.isAdmin)
            .putString(KEY_AVATAR, session.avatarUrl)
            .putLong(KEY_PREMIUM_UNTIL, session.premiumUntil ?: 0L)
            .putInt(KEY_ALERTS_PUBLISHED, session.alertsPublished)
            .apply()
        _session.value = session
        if (previous != session.userId) userListeners.forEach { it(session.userId) }
    }

    fun clear() {
        val previous = _session.value?.userId
        prefs.edit().clear().apply()
        _session.value = null
        if (previous != null) userListeners.forEach { it(null) }
    }

    private fun read(): Session? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return Session(
            token = token,
            userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
            displayName = prefs.getString(KEY_NAME, "").orEmpty(),
            isAdmin = prefs.getBoolean(KEY_ADMIN, false),
            avatarUrl = prefs.getString(KEY_AVATAR, null),
            premiumUntil = prefs.getLong(KEY_PREMIUM_UNTIL, 0L).takeIf { it > 0L },
            alertsPublished = prefs.getInt(KEY_ALERTS_PUBLISHED, 0)
        )
    }

    companion object {
        private const val PREFS_NAME = "expresdm_session"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_NAME = "display_name"
        private const val KEY_ADMIN = "is_admin"
        private const val KEY_AVATAR = "avatar_url"
        private const val KEY_PREMIUM_UNTIL = "premium_until"
        private const val KEY_ALERTS_PUBLISHED = "alerts_published"

        @Volatile
        private var instance: SessionStore? = null

        fun get(context: Context): SessionStore =
            instance ?: synchronized(this) {
                instance ?: SessionStore(context).also { instance = it }
            }
    }
}
