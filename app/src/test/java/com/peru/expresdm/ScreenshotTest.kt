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
                activity.findViewById<View>(R.id.rowSettings)?.performClick()
                idle()
                capture(activity, "06-ajustes")

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
                navHost.navController.navigate(
                    R.id.routeDetailFragment,
                    RouteDetailFragment.args("california-porvenir")
                )
                idle()
                capture(activity, "15-ficha-micro")
            }
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
        SessionStore.get(context).save(
            Session(
                token = "token-de-prueba",
                userId = "usuario-1",
                email = "ana.quispe@ejemplo.pe",
                displayName = "Ana María Quispe"
            )
        )
        val usage = RouteUsageStore.get(context)
        repeat(4) { usage.recordUse("california-porvenir") }
        repeat(2) { usage.recordUse("california-esperanza") }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    selectTab(activity, R.id.profileFragment)
                    capture(activity, "07-perfil-con-sesion")
                }
            }
        } finally {
            // La sesión de mentira no debe filtrarse a las demás capturas.
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

                activity.findViewById<View>(R.id.assistantBubble).performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog().window!!.decorView
                sheet.findViewById<View>(R.id.voiceOrb).visibility = View.VISIBLE
                capture(activity, "19-voz-en-el-asistente", sheet)
            }
        }
    }

    private fun selectTab(activity: Activity, itemId: Int) {
        activity.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = itemId
        idle()
    }

    /** Deja que se ejecuten las tareas pendientes del hilo principal. */
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
