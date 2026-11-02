package com.peru.expresdm

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.peru.expresdm.data.RouteUsageStore
import com.peru.expresdm.data.net.Session
import com.peru.expresdm.data.net.SessionStore
import com.peru.expresdm.ui.routes.RouteDetailFragment
import com.peru.expresdm.voice.VoiceOrbView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * Renderiza las pantallas con datos reales y guarda un PNG de cada una en
 * `app/build/screenshots`.
 *
 * Permite revisar el diseño sin emulador. No sustituye a probar en un
 * dispositivo: aquí no se dibuja el mapa, porque osmdroid necesita red y GPU.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {

    private val outputDir = File("build/screenshots").apply { mkdirs() }

    @Test
    fun `captura las pantallas principales`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                capture(activity, "01-inicio")

                selectTab(activity, R.id.reportsFragment)
                capture(activity, "02-alertas")

                selectTab(activity, R.id.profileFragment)
                capture(activity, "03-perfil")
            }
        }
    }

    /** Las mismas pantallas en español, para revisar la traducción. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura las pantallas en espanol`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                capture(activity, "04-inicio-espanol")

                selectTab(activity, R.id.reportsFragment)
                capture(activity, "05-alertas-espanol")

                selectTab(activity, R.id.profileFragment)
                // Ajustes: tema, idioma y muestra de la paleta.
                activity.findViewById<View>(R.id.btnProfileSettings)?.performClick()
                idle()
                capture(activity, "06-ajustes")

                // El aviso por SMS al llegar.
                activity.findViewById<ViewGroup>(R.id.settingsScroll)?.let { scroll ->
                    val card = activity.findViewById<View>(R.id.arrivalSms)
                    val top = IntArray(2).also { card.getLocationInWindow(it) }[1] -
                        IntArray(2).also { scroll.getLocationInWindow(it) }[1]
                    scroll.scrollTo(0, scroll.scrollY + top - 200)
                }
                capture(activity, "06-ajustes-sms")

                // El final de Ajustes: tipos de alerta, tema e idioma.
                activity.findViewById<ViewGroup>(R.id.settingsScroll)?.let { scroll ->
                    scroll.scrollTo(0, scroll.getChildAt(0).height + scroll.paddingBottom - scroll.height)
                }
                capture(activity, "06-ajustes-abajo")
            }
        }
    }

    /** Las pantallas a las que se llega desde otra: soporte y la ficha de un micro. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura soporte y la ficha del micro`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                selectTab(activity, R.id.profileFragment)
                activity.findViewById<View>(R.id.rowSupport)?.performClick()
                idle()
                capture(activity, "14-soporte")

                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.navHost) as NavHostFragment

                // Nosotros, desde el perfil.
                navHost.navController.popBackStack()
                idle()
                activity.findViewById<View>(R.id.rowAbout)?.performClick()
                idle()
                capture(activity, "14b-nosotros")
                activity.findViewById<ViewGroup>(R.id.aboutScroll)?.let { scroll ->
                    scroll.scrollTo(0, scroll.getChildAt(0).height + scroll.paddingBottom - scroll.height)
                }
                capture(activity, "14b-nosotros-abajo")

                // El asistente, como pantalla encima del mapa.
                activity.findViewById<View>(R.id.assistantTab)?.performClick()
                idle()
                capture(activity, "18-asistente")
                navHost.navController.navigate(
                    R.id.routeDetailFragment,
                    RouteDetailFragment.args(fichaRouteId())
                )
                idle()
                capture(activity, "15-ficha-micro")
                scrollFichaDown(activity)
                capture(activity, "15-ficha-micro-abajo")
            }
        }
    }

    /** La ficha de un micro en modo oscuro, arriba y abajo. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-night-xhdpi")
    fun `captura la ficha del micro en modo oscuro`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.navHost) as NavHostFragment
                navHost.navController.navigate(
                    R.id.routeDetailFragment,
                    RouteDetailFragment.args(fichaRouteId())
                )
                idle()
                capture(activity, "15-ficha-micro-oscuro")
                scrollFichaDown(activity)
                capture(activity, "15-ficha-micro-oscuro-abajo")
            }
        }
    }

    /** La ficha dentro de la hoja del inicio, al tocar la primera ruta de la lista. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura la ficha en la hoja del inicio`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.routesRecycler)
                capture(activity, "15-ficha-hoja-antes")
                list.getChildAt(0)?.performClick()
                idle()
                BottomSheetBehavior.from(activity.findViewById<View>(R.id.sheet)).state =
                    BottomSheetBehavior.STATE_EXPANDED
                capture(activity, "15-ficha-hoja")
                activity.findViewById<ViewGroup>(R.id.routePanel)?.let { scroll ->
                    scroll.scrollTo(0, scroll.getChildAt(0).height - scroll.height + scroll.paddingBottom)
                }
                capture(activity, "15-ficha-hoja-abajo")
            }
        }
    }

    /** Una ruta del catálogo con varias paradas, para que la ficha tenga de todo. */
    private fun fichaRouteId(): String =
        com.peru.expresdm.data.RouteRepository.all().let { routes ->
            (routes.firstOrNull { it.stops.size in 4..7 } ?: routes.first()).id
        }

    private fun scrollFichaDown(activity: Activity) {
        activity.findViewById<ViewGroup>(R.id.detailScroll)?.let { scroll ->
            scroll.scrollTo(0, Int.MAX_VALUE / 2)
        }
    }

    /**
     * El perfil de alguien que ya usa la app: con sesión abierta, rutas de
     * siempre y actividad. Sin esto solo se vería la pantalla recién instalada.
     */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura el perfil con sesion`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = Session(
            token = "token-de-prueba",
            userId = "usuario-1",
            email = "ana.quispe@ejemplo.pe",
            displayName = "Ana María Quispe"
        )
        SessionStore.get(context).save(session)
        val usage = RouteUsageStore.get(context)
        repeat(4) { usage.recordUse("california-porvenir") }
        repeat(2) { usage.recordUse("california-esperanza") }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    selectTab(activity, R.id.profileFragment)
                    keepSession(context, session)
                    capture(activity, "07-perfil-con-sesion")
                }
            }
        } finally {
            // La sesión de mentira no debe filtrarse a las demás capturas.
            SessionStore.get(context).clear()
        }
    }

    /** La hoja para borrar la cuenta, vacía y con el aviso de la palabra que falta. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura eliminar mi cuenta`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = Session(
            token = "token-de-prueba",
            userId = "usuario-4",
            email = "ana.quispe@ejemplo.pe",
            displayName = "Ana María Quispe"
        )
        SessionStore.get(context).save(session)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    selectTab(activity, R.id.profileFragment)
                    keepSession(context, session)
                    activity.findViewById<View>(R.id.rowDeleteAccount).performClick()
                    idle()
                    val sheet = ShadowDialog.getLatestDialog().window!!.decorView
                    capture(activity, "36-eliminar-cuenta", sheet)
                    // Sin escribir ELIMINAR, el botón no llama al servidor: avisa en el campo.
                    sheet.findViewById<View>(R.id.btnDeleteAccount).performClick()
                    idle()
                    capture(activity, "37-eliminar-cuenta-sin-palabra", sheet)
                    ShadowDialog.getLatestDialog().dismiss()
                    idle()
                }
            }
        } finally {
            SessionStore.get(context).clear()
        }
    }

    /** El perfil y la hoja de Subo Premium de una cuenta que ya lo tiene activo. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura el perfil premium activo`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = Session(
            token = "token-de-prueba",
            userId = "usuario-2",
            email = "stebandioses@gmail.com",
            displayName = "Esteban Zait Dioses Munoz",
            // 12 oct. 2027
            premiumUntil = 1823342400000L
        )
        SessionStore.get(context).save(session)
        val usage = RouteUsageStore.get(context)
        repeat(3) { usage.recordUse("california-porvenir") }
        repeat(2) { usage.recordUse("california-esperanza") }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    selectTab(activity, R.id.profileFragment)
                    keepSession(context, session)
                    // El uso va por cuenta: se apunta ya con la sesión puesta, y con
                    // las rutas que haya en el catálogo (el del servidor cambia).
                    com.peru.expresdm.data.RouteRepository.all().take(2).forEachIndexed { index, route ->
                        repeat(4 - index) { usage.recordUse(route.id) }
                    }
                    idle()
                    capture(activity, "07b-perfil-premium-activo")
                    activity.findViewById<View>(R.id.rowPremium).performClick()
                    idle()
                    keepSession(context, session)
                    val sheet = ShadowDialog.getLatestDialog().window!!.decorView
                    capture(activity, "17b-subo-premium-activo", sheet)
                }
            }
        } finally {
            SessionStore.get(context).clear()
        }
    }

    /** En modo oscuro, para comprobar que el tema responde. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun `captura las pantallas en modo oscuro`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                capture(activity, "07-inicio-oscuro")

                selectTab(activity, R.id.reportsFragment)
                capture(activity, "08-alertas-oscuro")
            }
        }
    }

    /** La hoja "¿A dónde vas?", que se abre al tocar el buscador del inicio. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura a donde vas`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.searchCard).performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog().window!!.decorView
                capture(activity, "09-a-donde-vas", sheet)
            }
        }
    }

    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-night-xhdpi")
    fun `captura a donde vas en modo oscuro`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.searchCard).performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog().window!!.decorView
                capture(activity, "10-a-donde-vas-oscuro", sheet)
            }
        }
    }

    /** Subo Premium, tal como lo ve una cuenta gratuita desde su perfil. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura subo premium`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                selectTab(activity, R.id.profileFragment)
                capture(activity, "16-perfil-premium")
                activity.findViewById<View>(R.id.rowPremium).performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog().window!!.decorView
                capture(activity, "17-subo-premium", sheet)
            }
        }
    }

    /** Las tres alturas de la hoja del inicio: reposo (carrusel), arriba (tarjetas) y mínima. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-night-xhdpi")
    fun `captura las alturas de la hoja`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = BottomSheetBehavior.from(activity.findViewById<View>(R.id.sheet))
                capture(activity, "11-hoja-reposo")

                sheet.state = BottomSheetBehavior.STATE_EXPANDED
                capture(activity, "12-hoja-arriba")

                sheet.state = BottomSheetBehavior.STATE_COLLAPSED
                capture(activity, "13-hoja-minima")
            }
        }
    }

    /** El inicio con ubicación en Trujillo: las líneas cercanas y, con el filtro "Todas", el catálogo. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura las rutas cercanas`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.navHost) as NavHostFragment
                val routes = navHost.childFragmentManager.primaryNavigationFragment!!
                androidx.lifecycle.ViewModelProvider(routes)[com.peru.expresdm.ui.routes.RoutesViewModel::class.java]
                    .onLocationChanged(-8.1116, -79.0287)
                // El cálculo de las cercanas corre fuera del hilo principal.
                Thread.sleep(1_500)
                capture(activity, "20-rutas-cerca")

                activity.findViewById<View>(R.id.chipAll).performClick()
                capture(activity, "21-rutas-todas")
            }
        }
    }

    /** La esfera de la voz: pequeña sobre el mapa y en una franja dentro del asistente. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura la esfera de la voz`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.TextView>(R.id.voiceBarText).setText(R.string.voice_prompt)
                activity.findViewById<VoiceOrbView>(R.id.voiceBarOrb).apply {
                    compact = true
                    mode = VoiceOrbView.Mode.SPEAKING
                }
                activity.findViewById<View>(R.id.voiceBar).visibility = View.VISIBLE
                capture(activity, "18-voz-en-el-mapa")

                activity.findViewById<View>(R.id.assistantTab).performClick()
                idle()
                activity.findViewById<View>(R.id.voiceOrb).visibility = View.VISIBLE
                capture(activity, "19-voz-en-el-asistente")
            }
        }
    }

    /** Alertas con datos de ejemplo, la hoja "¿Qué está pasando?" y el asistente con su saludo. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura alertas crear alerta y asistente`() = captureAlertas("alertas")

    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-night-xhdpi")
    fun `captura alertas crear alerta y asistente en modo oscuro`() = captureAlertas("alertas-oscuro")

    private fun captureAlertas(suffix: String) {
        val now = System.currentTimeMillis()
        val minute = 60_000L
        val samples = listOf(
            com.peru.expresdm.data.RoadAlert(
                id = "ej-1", type = com.peru.expresdm.data.AlertType.TRAFFIC,
                latitude = -8.11, longitude = -79.03, placeName = "Av. España",
                comment = "Cola larga desde el óvalo, mejor tomar Av. América",
                createdAt = now - 12 * minute, confirmations = 3
            ),
            com.peru.expresdm.data.RoadAlert(
                id = "ej-2", type = com.peru.expresdm.data.AlertType.ROADWORK,
                latitude = -8.10, longitude = -79.02, placeName = "Av. Larco",
                comment = null, createdAt = now - 40 * minute
            ),
            com.peru.expresdm.data.RoadAlert(
                id = "ej-3", type = com.peru.expresdm.data.AlertType.DANGER,
                latitude = -8.12, longitude = -79.04, placeName = "Óvalo Grau",
                comment = null, createdAt = now - 90 * minute
            )
        )
        // Las alertas viven en un objeto: se cambian a mano y luego se vacían.
        val field = com.peru.expresdm.data.AlertRepository::class.java.getDeclaredField("_alerts")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(com.peru.expresdm.data.AlertRepository)
            as kotlinx.coroutines.flow.MutableStateFlow<List<com.peru.expresdm.data.RoadAlert>>

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                selectTab(activity, R.id.reportsFragment)
                capture(activity, "20-$suffix-vacia")

                activity.findViewById<View>(R.id.btnReport).performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog().window!!.decorView
                capture(activity, "21-$suffix-crear", sheet)
                // Elige "Tráfico", la primera casilla.
                val grid = sheet.findViewById<ViewGroup>(R.id.typeGrid)
                (grid.getChildAt(0) as ViewGroup).getChildAt(0).performClick()
                capture(activity, "22-$suffix-crear-trafico", sheet)
                ShadowDialog.getLatestDialog().dismiss()
                idle()

                try {
                    flow.value = samples
                    idle()
                    capture(activity, "23-$suffix-lista")
                } finally {
                    flow.value = emptyList()
                }

                selectTab(activity, R.id.routesFragment)
                activity.findViewById<View>(R.id.assistantTab).performClick()
                idle()
                // El asistente ya no es una ventana: es una pantalla dentro de la Activity.
                capture(activity, "24-$suffix-asistente")
            }
        }
    }

    /** La ficha de una línea con ida y vuelta: el selector de sentido, en los dos. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura la ida y la vuelta de una ruta`() {
        val ida = com.peru.expresdm.data.RouteRepository.byId(fichaRouteId())!!
        val vuelta = ida.copy(
            id = "vuelta-de-prueba",
            origin = ida.destination,
            destination = ida.origin,
            stops = ida.stops.reversed(),
            createdByUser = false
        )
        com.peru.expresdm.data.RouteRepository.add(vuelta)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val navHost = activity.supportFragmentManager
                        .findFragmentById(R.id.navHost) as NavHostFragment
                    navHost.navController.navigate(R.id.routeDetailFragment, RouteDetailFragment.args(ida.id))
                    idle()
                    capture(activity, "30-ficha-ida")
                    activity.findViewById<View>(R.id.btnDirectionVuelta).performClick()
                    idle()
                    capture(activity, "31-ficha-vuelta")
                }
            }
        } finally {
            com.peru.expresdm.data.RouteRepository.remove(vuelta.id)
        }
    }

    /** El perfil con Premium a punto de vencer, el historial de compras y el micro de un reporte. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura el aviso de vencimiento y el historial de compras`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = Session(
            token = "token-de-prueba",
            userId = "usuario-3",
            email = "ana.quispe@ejemplo.pe",
            displayName = "Ana María Quispe",
            premiumUntil = System.currentTimeMillis() + 2L * 24 * 60 * 60 * 1000
        )
        SessionStore.get(context).save(session)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    selectTab(activity, R.id.profileFragment)
                    keepSession(context, session)
                    capture(activity, "32-perfil-premium-vence")
                    activity.findViewById<View>(R.id.rowPremium).performClick()
                    idle()
                    Thread.sleep(3_000)
                    idle()
                    keepSession(context, session.copy(premiumUntil = null))
                    val premium = ShadowDialog.getLatestDialog().window!!.decorView
                    // Mercado Pago depende del servidor: aquí se enseña a mano para ver cómo queda.
                    premium.findViewById<View>(R.id.paymentSection).visibility = View.VISIBLE
                    premium.findViewById<View>(R.id.mercadoPagoSection).visibility = View.VISIBLE
                    premium.findViewById<View>(R.id.yapeTitle).visibility = View.VISIBLE
                    premium.findViewById<ViewGroup>(R.id.paymentSection).let { card ->
                        (premium.findViewById<View>(R.id.paymentSection).parent as View).let { content ->
                            (content.parent as? ViewGroup)?.scrollTo(0, card.top)
                        }
                    }
                    idle()
                    capture(activity, "35-premium-mercado-pago", premium)
                    ShadowDialog.getLatestDialog().dismiss()
                    keepSession(context, session)
                    idle()
                    activity.findViewById<View>(R.id.rowPurchases).performClick()
                    idle()
                    Thread.sleep(3_000)
                    idle()
                    val sheet = ShadowDialog.getLatestDialog().window!!.decorView
                    capture(activity, "33-historial-compras", sheet)
                    ShadowDialog.getLatestDialog().dismiss()
                    idle()

                    selectTab(activity, R.id.reportsFragment)
                    activity.findViewById<View>(R.id.btnReport).performClick()
                    idle()
                    val report = ShadowDialog.getLatestDialog().window!!.decorView
                    // "Cobro de más", que pregunta de qué micro.
                    val grid = report.findViewById<ViewGroup>(R.id.typeGrid)
                    (grid.getChildAt(1) as ViewGroup).getChildAt(0).performClick()
                    idle()
                    capture(activity, "34-reporte-micro", report)
                }
            }
        } finally {
            SessionStore.get(context).clear()
        }
    }

    /** Formas de llegar con transbordo: la foto de cada micro, mitad y mitad; y una sin foto en una mitad. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura el viaje con transbordo`() {
        // Fotos de mentira, ya en la memoria de RouteImages, para no ir a la red.
        val cacheField = com.peru.expresdm.ui.RouteImages::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(com.peru.expresdm.ui.RouteImages) as android.util.LruCache<String, Bitmap>
        fun photo(url: String, top: Int, bottom: Int) {
            val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 600f, 400f, top, bottom, android.graphics.Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, 600f, 400f, paint)
            // Un "micro" en el centro, para ver que se recorta al medio.
            canvas.drawRect(200f, 140f, 400f, 260f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
            cache.put(url, bitmap)
        }
        photo("https://fotos.test/a.jpg", 0xFF1E88E5.toInt(), 0xFF0D47A1.toInt())
        photo("https://fotos.test/b.jpg", 0xFFE53935.toInt(), 0xFFFFB300.toInt())

        val all = com.peru.expresdm.data.RouteRepository.all()
        val first = all[0].copy(imageUrl = "https://fotos.test/a.jpg", vehicleName = "Señor de los Milagros")
        val second = all[1].copy(imageUrl = "https://fotos.test/b.jpg", vehicleName = "Huanchaco")
        fun journey(vararg routes: com.peru.expresdm.data.BusRoute) = com.peru.expresdm.data.Journey(
            legs = listOf(com.peru.expresdm.data.JourneyLeg.Walk(120.0, 2)) + routes.map {
                com.peru.expresdm.data.JourneyLeg.Ride(it, it.stops.first(), it.stops.last(), 15)
            },
            totalMinutes = 42, walkMeters = 120.0, fare = 5.5
        )
        val journeys = listOf(
            journey(first, second),
            journey(first, second.copy(id = "sin-foto", imageUrl = null)),
            journey(first)
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (carousel in listOf(true, false)) {
                    val adapter = com.peru.expresdm.ui.routes.JourneyAdapter {}.apply { this.carousel = carousel }
                    val recycler = androidx.recyclerview.widget.RecyclerView(activity).apply {
                        layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                            activity,
                            if (carousel) androidx.recyclerview.widget.RecyclerView.HORIZONTAL
                            else androidx.recyclerview.widget.RecyclerView.VERTICAL,
                            false
                        )
                        this.adapter = adapter
                        setPadding(dp(activity, 12), dp(activity, 40), dp(activity, 12), 0)
                        clipToPadding = false
                    }
                    val dialog = android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
                    dialog.setContentView(recycler)
                    dialog.show()
                    adapter.submitList(journeys)
                    idle()
                    capture(activity, if (carousel) "40-transbordo-carrusel" else "41-transbordo-lista", dialog.window!!.decorView)
                    if (carousel) {
                        recycler.scrollBy(dp(activity, 500), 0)
                        capture(activity, "40b-transbordo-carrusel-sin-foto", dialog.window!!.decorView)
                    }
                    dialog.dismiss()
                    idle()
                }
            }
        }
    }

    /** La ventana del pago con Mercado Pago dentro de la app, cargando y con el aviso de error. */
    @Test
    @Config(qualifiers = "es-rPE-w411dp-h891dp-xhdpi")
    fun `captura el pago dentro de la app`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = Session(
            token = "token-de-prueba", userId = "usuario-4", email = "ana@ejemplo.pe", displayName = "Ana"
        )
        SessionStore.get(context).save(session)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    keepSession(context, session)
                    com.peru.expresdm.ui.account.PremiumPayDialog.show(activity.supportFragmentManager, 30)
                    idle()
                    val window = ShadowDialog.getLatestDialog().window!!.decorView
                    capture(activity, "42-pago-en-la-app", window)
                    window.findViewById<View>(R.id.payProgress).visibility = View.GONE
                    window.findViewById<View>(R.id.payError).visibility = View.VISIBLE
                    capture(activity, "43-pago-en-la-app-error", window)
                }
            }
        } finally {
            SessionStore.get(context).clear()
        }
    }

    private fun selectTab(activity: Activity, itemId: Int) {
        activity.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = itemId
        idle()
    }

    /** Deja que se ejecuten las tareas pendientes del hilo principal. */
    /**
     * Al abrir, la app pregunta al servidor por la sesión y, como el token es
     * de mentira, la cierra. Se vuelve a poner justo antes de la captura.
     */
    private fun keepSession(context: Context, session: Session) {
        SessionStore.get(context).save(session)
        idle()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** [decor] permite capturar una hoja o un diálogo, que viven en su propia ventana. */
    private fun capture(activity: Activity, name: String, decor: View = activity.window.decorView) {
        idle()

        val width = dp(activity, 411)
        val height = dp(activity, 891)

        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, height)
        idle()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(themeBackground(activity))
        decor.draw(Canvas(bitmap))

        FileOutputStream(File(outputDir, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun themeBackground(activity: Activity): Int {
        val value = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        return value.data
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
