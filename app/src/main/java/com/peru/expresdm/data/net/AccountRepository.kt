package com.peru.expresdm.data.net

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Cuentas de usuario contra el servidor.
 *
 * La app sigue funcionando sin cuenta: se pueden ver rutas y alertas. La sesión
 * hace falta para publicar alertas, votar, compartir rutas y usar el asistente,
 * porque todo eso escribe en el servidor o gasta su cuota de IA.
 */
object AccountRepository {

    private lateinit var store: SessionStore
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Sesión actual, observable desde la interfaz. */
    val session: StateFlow<Session?> get() = store.session

    val token: String? get() = store.token

    val isSignedIn: Boolean get() = store.token != null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!::store.isInitialized) store = SessionStore.get(context)
    }

    /**
     * Pide una cuenta nueva. `true`: quedó creada y con la sesión abierta.
     * `false`: el servidor mandó un código al correo y la cuenta se crea al
     * confirmarlo con [confirmRegistration].
     */
    suspend fun register(email: String, password: String, displayName: String): ApiResult<Boolean> {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("displayName", displayName.trim())
        return when (val result = ApiClient.post("/api/auth/register", body)) {
            is ApiResult.Failure -> result
            is ApiResult.Success ->
                if (result.data.optBoolean("verificationRequired")) {
                    ApiResult.Success(false)
                } else {
                    when (val opened = open(result.data)) {
                        is ApiResult.Failure -> opened
                        is ApiResult.Success -> ApiResult.Success(true)
                    }
                }
        }
    }

    /** El segundo paso del registro: con el código del correo, la cuenta se crea y se entra. */
    suspend fun confirmRegistration(email: String, code: String): ApiResult<Session> =
        authenticate(
            "/api/auth/register/verify",
            JSONObject().put("email", email.trim()).put("code", code.trim())
        )

    suspend fun login(email: String, password: String): ApiResult<Session> =
        authenticate(
            "/api/auth/login",
            JSONObject().put("email", email.trim()).put("password", password)
        )

    suspend fun googleChallenge(): ApiResult<JSONObject> =
        ApiClient.post("/api/auth/google/challenge", JSONObject())

    suspend fun loginGoogle(idToken: String, nonce: String, existingPassword: String): ApiResult<Session> =
        authenticate("/api/auth/google", JSONObject()
            .put("idToken", idToken).put("nonce", nonce)
            .apply { if (existingPassword.isNotBlank()) put("existingPassword", existingPassword) })

    fun signOut() {
        store.clear()
        scope.launch {
            try { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* La sesión local ya está cerrada. */ }
        }
    }

    /**
     * Cambia el nombre con el que se ve al usuario en alertas y rutas.
     *
     * El servidor manda el nombre ya limpio (sin espacios de sobra), y ese es
     * el que se guarda, para que el teléfono muestre lo mismo que ven los demás.
     */
    suspend fun updateName(displayName: String): ApiResult<Session> {
        val current = session.value ?: return ApiResult.Failure(ApiFailure.UNAUTHORIZED)
        val body = JSONObject().put("displayName", displayName.trim())
        return when (val result = ApiClient.post("/api/me", body, current.token)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val saved = result.data.optString("displayName").takeIf { it.isNotBlank() }
                    ?: displayName.trim()
                val updated = current.copy(displayName = saved)
                store.save(updated)
                ApiResult.Success(updated)
            }
        }
    }

    /**
     * Comprueba que la sesión guardada sigue valiendo. Si el servidor la
     * rechaza (cambió el secreto, caducó, se borró la cuenta) se cierra aquí,
     * en lugar de fallar más tarde en medio de otra cosa.
     */
    suspend fun refresh() {
        if (!isSignedIn || !ApiClient.isConfigured) return
        when (val result = ApiClient.get("/api/me", token)) {
            is ApiResult.Failure -> if (result.reason == ApiFailure.UNAUTHORIZED) signOut()
            is ApiResult.Success -> {
                // El permiso de administrador y Premium se dan y se quitan en el
                // servidor: aquí se ponen al día sin obligar a cerrar sesión.
                val current = session.value ?: return
                val updated = current.copy(
                    isAdmin = isAdminRole(result.data),
                    avatarUrl = avatarOf(result.data),
                    premiumUntil = premiumOf(result.data),
                    alertsPublished = result.data.optInt("alertsPublished", current.alertsPublished)
                )
                if (updated != current) store.save(updated)
            }
        }
    }

    /** La cuenta acaba de publicar una alerta: el resumen del perfil la cuenta sin esperar al servidor. */
    fun noteAlertPublished() {
        val current = session.value ?: return
        store.save(current.copy(alertsPublished = current.alertsPublished + 1))
    }

    /**
     * Pone la foto de perfil (ya subida y revisada) o la quita con `null`.
     * Devuelve la sesión con la foto que el servidor dejó guardada.
     */
    suspend fun setAvatar(avatarUrl: String?): ApiResult<Session> {
        val current = session.value ?: return ApiResult.Failure(ApiFailure.UNAUTHORIZED)
        val body = JSONObject().put("avatarUrl", avatarUrl ?: JSONObject.NULL)
        return when (val result = ApiClient.post("/api/me/avatar", body, current.token)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val updated = current.copy(avatarUrl = avatarOf(result.data))
                store.save(updated)
                ApiResult.Success(updated)
            }
        }
    }

    /** Pide un código al correo para cambiar la contraseña. */
    suspend fun forgotPassword(email: String): ApiResult<JSONObject> =
        ApiClient.post("/api/auth/forgot", JSONObject().put("email", email.trim()))

    /** Cambia la contraseña con el código recibido y deja la sesión abierta. */
    suspend fun resetPassword(email: String, code: String, password: String): ApiResult<Session> =
        authenticate(
            "/api/auth/reset",
            JSONObject().put("email", email.trim()).put("code", code.trim()).put("password", password)
        )

    /** El servidor manda `role`: client, contributor, admin o super_admin. Los tres últimos graban rutas. */
    private fun isAdminRole(user: JSONObject): Boolean = user.optString("role") in ROUTE_ROLES

    private fun avatarOf(user: JSONObject): String? =
        if (user.isNull("avatarUrl")) null else user.optString("avatarUrl").takeIf { it.isNotBlank() }

    private fun premiumOf(user: JSONObject): Long? =
        if (user.isNull("premiumUntil")) null else user.optLong("premiumUntil").takeIf { it > 0L }

    private val ROUTE_ROLES = setOf("contributor", "admin", "super_admin")

    private suspend fun authenticate(path: String, body: JSONObject): ApiResult<Session> =
        when (val result = ApiClient.post(path, body)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> open(result.data)
        }

    /** Guarda la sesión que abrió el servidor. */
    private fun open(data: JSONObject): ApiResult<Session> {
        val token = data.optString("token")
        val user = data.optJSONObject("user")
        if (token.isBlank() || user == null) return ApiResult.Failure(ApiFailure.SERVER)
        val session = Session(
            token = token,
            userId = user.optString("id"),
            email = user.optString("email"),
            displayName = user.optString("displayName"),
            isAdmin = isAdminRole(user),
            avatarUrl = avatarOf(user),
            premiumUntil = premiumOf(user)
        )
        store.save(session)
        return ApiResult.Success(session)
    }
}
