package com.peru.expresdm.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peru.expresdm.R
import com.peru.expresdm.data.FrequentRoutes
import com.peru.expresdm.data.ProfileStats
import com.peru.expresdm.data.RouteRepository
import com.peru.expresdm.data.RouteUsageStore
import com.peru.expresdm.data.SavedRoutes
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.data.net.ApiFailure
import com.peru.expresdm.data.net.ApiResult
import com.peru.expresdm.ui.routes.RouteListItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lo que necesita la pantalla de perfil: quién eres, lo que llevas hecho y las
 * rutas que más usas.
 *
 * Antes esta pantalla reutilizaba el ViewModel del inicio, que arrastra la
 * búsqueda, el mapa y la ubicación. Aquí solo hace falta esto.
 */
class ProfileViewModel(app: Application) : AndroidViewModel(app) {

    private val usage = RouteUsageStore.get(app)

    /** Favoritas elegidas por el uso, sin que el usuario marque nada. */
    val favorites: StateFlow<List<RouteListItem>> =
        combine(usage.uses, RouteRepository.routes) { uses, _ ->
            FrequentRoutes.rank(uses)
                .mapNotNull { RouteRepository.byId(it) }
                .map { RouteListItem(it) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<ProfileStats> =
        combine(
            RouteRepository.routes, SavedRoutes.get(app).ids, AccountRepository.session, usage.uses
        ) { routes, saved, session, uses ->
            ProfileStats.of(routes, saved, session?.alertsPublished ?: 0, uses)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileStats.EMPTY)

    /** Evita que dos toques seguidos manden el nombre dos veces. */
    private var saving = false

    private val _messages = MutableSharedFlow<ProfileMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<ProfileMessage> = _messages.asSharedFlow()

    fun rename(newName: String) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            val result = AccountRepository.updateName(newName)
            saving = false
            _messages.tryEmit(
                when (result) {
                    is ApiResult.Success -> ProfileMessage(R.string.profile_name_saved, done = true)
                    is ApiResult.Failure -> ProfileMessage(messageFor(result.reason), text = result.message)
                }
            )
        }
    }

    private fun messageFor(reason: ApiFailure): Int = when (reason) {
        ApiFailure.NETWORK, ApiFailure.NOT_CONFIGURED -> R.string.account_offline
        ApiFailure.UNAUTHORIZED -> R.string.profile_name_session_expired
        ApiFailure.REJECTED, ApiFailure.SERVER -> R.string.profile_name_failed
    }
}

/**
 * Un aviso para el usuario: [text] es lo que dijo el servidor, si dijo algo, y
 * si no se usa [fallback]. [done] marca que la operación salió bien.
 */
data class ProfileMessage(
    val fallback: Int,
    val text: String? = null,
    val done: Boolean = false
)
