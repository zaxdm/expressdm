package com.peru.expresdm.tracking

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.peru.expresdm.data.AlertType
import com.peru.expresdm.data.AppSettings
import com.peru.expresdm.data.RouteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Recibe los avisos al momento: el servidor manda uno en cuanto alguien publica
 * una alerta cerca de donde anda este teléfono o en una ruta que usa, y cuando
 * cambia una ruta que el usuario tiene guardada (Premium). También cuando sale
 * una versión nueva de la app.
 *
 * Llegan solo los datos; la notificación se arma aquí, en el idioma del
 * teléfono y respetando lo que el usuario eligió en Ajustes.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushRegistration.onNewToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["kind"] == KIND_ROUTE_CHANGE) {
            onRouteChanged(data)
            return
        }
        if (data["kind"] == KIND_APP_UPDATE) {
            // Se comprueba contra la web: quien ya la tiene puesta no recibe nada.
            scope.launch { UpdateWatchWorker.checkNow(applicationContext) }
            return
        }
        val id = data["alertId"]?.takeIf { it.isNotBlank() } ?: return
        // Un tipo que esta versión no conoce: mejor callar que avisar a medias.
        val type = AlertType.entries.firstOrNull { it.name == data["type"] } ?: return

        val settings = AppSettings.get(this)
        if (!settings.alertNotifications || type !in settings.alertTypes.value) return
        if (!AlertNotifier.markSeen(this, id)) return

        AlertNotifier.show(
            context = this,
            id = id,
            type = type,
            routeId = data["routeId"]?.takeIf { it.isNotBlank() },
            placeName = data["placeName"],
            comment = data["comment"]
        )
    }

    private fun onRouteChanged(data: Map<String, String>) {
        val routeId = data["routeId"]?.takeIf { it.isNotBlank() } ?: return
        if (!AppSettings.get(this).alertNotifications) return
        // El catálogo guardado ya es viejo: se pide el nuevo para que, al tocar
        // el aviso, la ficha enseñe la ruta como quedó.
        scope.launch { RouteRepository.refresh() }
        RouteChangeNotifier.show(
            context = this,
            routeId = routeId,
            routeName = data["routeName"]?.takeIf { it.isNotBlank() }
                ?: RouteRepository.byId(routeId)?.name
                ?: return,
            changes = data["changes"].orEmpty()
        )
    }

    private companion object {
        const val KIND_ROUTE_CHANGE = "route_change"
        const val KIND_APP_UPDATE = "app_update"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
