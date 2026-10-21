package com.peru.expresdm

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import com.peru.expresdm.data.AppUpdates
import com.peru.expresdm.data.Premium
import com.peru.expresdm.data.RouteRepository
import com.peru.expresdm.data.SavedRoutes
import com.peru.expresdm.data.SharedLink
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.databinding.ActivityMainBinding
import com.peru.expresdm.tracking.ArrivalAlarmService
import com.peru.expresdm.tracking.BackgroundAccess
import com.peru.expresdm.tracking.PushRegistration
import com.peru.expresdm.tracking.UpdateWatchWorker
import com.peru.expresdm.ui.UpdatePrompt
import com.peru.expresdm.ui.assistant.AssistantSheet
import com.peru.expresdm.ui.routes.REQUEST_OPEN_ASSISTANT
import com.peru.expresdm.ui.routes.RouteDetailFragment
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Única Activity de la app: aloja el grafo de navegación y la barra inferior.
 *
 * Con NavigationUI, cada pestaña reutiliza su destino en lugar de apilar
 * pantallas nuevas, así que el botón atrás se comporta como se espera.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var navHost: NavHostFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // adb shell am start -n <paquete>/com.peru.expresdm.MainActivity --ez debug_premium true
        if (BuildConfig.DEBUG) {
            Premium.debugForced = intent.getBooleanExtra(EXTRA_DEBUG_PREMIUM, Premium.debugForced)
            // … --ez debug_update true: ofrece la versión de la web como si esta fuera vieja.
            AppUpdates.debugPretendOld = intent.getBooleanExtra(EXTRA_DEBUG_UPDATE, AppUpdates.debugPretendOld)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navHost = supportFragmentManager
            .findFragmentById(R.id.navHost) as NavHostFragment
        navController = navHost.navController

        binding.bottomNav.setupWithNavController(navController)
        setUpAssistantTab()

        // Las pantallas secundarias (ajustes, soporte, ficha del micro, nueva ruta) ocupan todo el alto.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val visibility = if (destination.id in TOP_LEVEL_DESTINATIONS) View.VISIBLE else View.GONE
            binding.bottomNav.visibility = visibility
            binding.navDivider.visibility = visibility
        }

        applyWindowInsets()

        // Al girar la pantalla el enlace ya se atendió: la ficha sigue abierta.
        if (savedInstanceState == null) openSharedRoute(intent)

        // Premium y el permiso de administrador se activan en el servidor: al
        // abrir la app se pregunta cómo está la cuenta.
        if (savedInstanceState == null) lifecycleScope.launch { AccountRepository.refresh() }

        if (savedInstanceState == null) offerUpdate(asked = intent.getBooleanExtra(UpdateWatchWorker.EXTRA_OFFER_UPDATE, false))

        // Al activar un aviso de bajada se pide, una vez, que el teléfono no
        // cierre la app por ahorrar batería: sin eso el aviso no llega.
        lifecycleScope.launch {
            ArrivalAlarmService.state.map { it.target != null }.distinctUntilChanged()
                .collect { watching -> if (watching) BackgroundAccess.askOnce(this@MainActivity) }
        }

        // Al entrar o salir de la cuenta, el servidor sabe de quién es este
        // teléfono: así no le avisa a alguien de su propia alerta.
        lifecycleScope.launch {
            AccountRepository.session.map { it?.token }.distinctUntilChanged().drop(1)
                .collect { PushRegistration.sync(this@MainActivity) }
        }

        // Y sabe qué rutas tiene guardadas, para avisarle si cambian.
        lifecycleScope.launch {
            SavedRoutes.get(this@MainActivity).ids.drop(1)
                .collect { PushRegistration.sync(this@MainActivity) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSharedRoute(intent)
        if (intent.getBooleanExtra(UpdateWatchWorker.EXTRA_OFFER_UPDATE, false)) offerUpdate(asked = true)
    }

    /**
     * Si en la web hay una versión más nueva, la ofrece. Al abrir la app, una
     * vez al día; con [asked] (se tocó el aviso), siempre.
     */
    private fun offerUpdate(asked: Boolean) {
        lifecycleScope.launch {
            val update = AppUpdates.check() ?: return@launch
            if (asked || AppUpdates.shouldOffer(this@MainActivity, update)) UpdatePrompt.offer(this@MainActivity, update)
        }
    }

    /** Si la app se abrió desde un enlace compartido, enseña la ficha de ese micro. */
    private fun openSharedRoute(intent: Intent?) {
        val routeId = SharedLink.routeIdOf(intent?.data) ?: return
        if (showRoute(routeId)) return

        // Puede ser una ruta más nueva que el catálogo guardado: se pide y se reintenta.
        lifecycleScope.launch {
            RouteRepository.refresh()
            if (!showRoute(routeId)) {
                Toast.makeText(this@MainActivity, R.string.shared_route_missing, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showRoute(routeId: String): Boolean {
        if (RouteRepository.byId(routeId) == null) return false
        navController.navigate(R.id.routeDetailFragment, RouteDetailFragment.args(routeId))
        return true
    }

    /**
     * La pestaña Asistente abre el chat como una pantalla encima del mapa del
     * inicio (que es quien atiende sus órdenes de voz). Mientras está abierto,
     * la pestaña se ve marcada; al cerrarlo vuelve la de antes.
     */
    private fun setUpAssistantTab() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.assistantTab) {
                openAssistant()
                true
            } else {
                // Otra pestaña: el chat se cierra y se ve esa pantalla.
                closeAssistant()
                NavigationUI.onNavDestinationSelected(item, navController)
            }
        }
        binding.bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.assistantTab) openAssistant()
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (f is AssistantSheet) binding.bottomNav.menu.findItem(R.id.assistantTab)?.isChecked = true
            }

            override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                if (f !is AssistantSheet) return
                val current = navController.currentDestination?.id ?: return
                binding.bottomNav.menu.findItem(current)?.isChecked = true
            }
        }, true)
    }

    /** Cierra el chat del asistente si está abierto sobre el mapa. */
    private fun closeAssistant() {
        val map = navHost.childFragmentManager.primaryNavigationFragment ?: return
        (map.childFragmentManager.findFragmentByTag(AssistantSheet.TAG) as? AssistantSheet)?.dismiss()
    }

    /** Vuelve al mapa si hace falta y le pide que abra el asistente. */
    private fun openAssistant() {
        if (navController.currentDestination?.id != R.id.routesFragment) {
            binding.bottomNav.menu.findItem(R.id.routesFragment)
                ?.let { NavigationUI.onNavDestinationSelected(it, navController) }
        }
        // Si el mapa todavía no está listo, el aviso espera a que lo esté.
        navHost.childFragmentManager.setFragmentResult(REQUEST_OPEN_ASSISTANT, Bundle.EMPTY)
    }

    /** Deja libres la barra de estado y la de gestos. */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private companion object {
        const val EXTRA_DEBUG_PREMIUM = "debug_premium"
        const val EXTRA_DEBUG_UPDATE = "debug_update"

        val TOP_LEVEL_DESTINATIONS = setOf(
            R.id.routesFragment,
            R.id.reportsFragment,
            R.id.profileFragment
        )
    }
}
