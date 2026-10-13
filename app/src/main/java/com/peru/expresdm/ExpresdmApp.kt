package com.peru.expresdm

import android.app.Application
import android.util.Log
import com.peru.expresdm.data.AlertRepository
import com.peru.expresdm.data.AppSettings
import com.peru.expresdm.data.KnownPlaces
import com.peru.expresdm.data.RouteRepository
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.tracking.AlertWatchWorker
import com.peru.expresdm.tracking.PushRegistration
import com.peru.expresdm.tracking.RouteRecorderService
import com.peru.expresdm.tracking.UpdateWatchWorker
import org.osmdroid.config.Configuration

/**
 * Configuración que se hace una sola vez al arrancar.
 *
 * OpenStreetMap exige un user agent propio por aplicación: sin él, el servidor
 * de teselas devuelve 403 y el mapa aparece en blanco. La caché va al
 * almacenamiento privado de la app, así no hace falta ningún permiso de disco.
 */
class ExpresdmApp : Application() {

    private companion object {
        const val TILE_KEEP_MS = 30L * 24 * 60 * 60 * 1000
        const val TILE_CACHE_MAX_BYTES = 400L * 1024 * 1024
        const val TILE_CACHE_TRIM_BYTES = 350L * 1024 * 1024
    }

    override fun onCreate() {
        super.onCreate()

        // El tema debe quedar fijado antes de que se cree la primera pantalla,
        // o la app parpadearía del claro al oscuro al abrirse.
        AppSettings.get(this).applySavedTheme()

        AccountRepository.init(this)
        RouteRepository.init(this)
        // Los sitios conocidos se leen aparte: son más de mil y no hay prisa.
        Thread { KnownPlaces.load(this) }.start()
        AlertRepository.init(this)
        // Si Android cerró la app mientras se grababa una ruta, se recupera.
        RouteRecorderService.restoreInterrupted(this)
        // La revisión de alertas cercanas sigue programada aunque se cierre la app.
        try {
            AlertWatchWorker.sync(this)
            PushRegistration.sync(this)
            UpdateWatchWorker.sync(this)
        } catch (e: IllegalStateException) {
            // WorkManager sin iniciar (pasa en las pruebas): la app sigue sin los avisos.
            Log.w("ExpresdmApp", "No se pudo programar la revisión de alertas", e)
        }

        Configuration.getInstance().apply {
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = java.io.File(filesDir, "osmdroid")
            osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles")
            // Sin internet se ve el mapa que ya se vio: lo visto se guarda un mes
            // más de lo que pide el servidor, y con sitio para toda la ciudad.
            expirationExtendedDuration = TILE_KEEP_MS
            tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES
            tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
        }
    }
}
