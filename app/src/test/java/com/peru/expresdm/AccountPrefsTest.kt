package com.peru.expresdm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.peru.expresdm.data.AccountPrefs
import com.peru.expresdm.data.RouteUsageStore
import com.peru.expresdm.data.net.Session
import com.peru.expresdm.data.net.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lo que es de la persona se guarda aparte para cada cuenta del teléfono. */
@RunWith(RobolectricTestRunner::class)
class AccountPrefsTest {

    private fun sesion(id: String) = Session(token = "token-$id", userId = id, email = "$id@example.com", displayName = id)

    @Test
    fun `cada cuenta ve solo sus rutas mas usadas`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sesiones = SessionStore.get(context)
        val usos = RouteUsageStore.get(context)
        sesiones.clear()

        // Lo hecho antes de tener cuenta se lo queda la primera que entra.
        usos.recordUse("ruta-de-antes")
        sesiones.save(sesion("ana"))
        assertEquals(1, usos.uses.value["ruta-de-antes"])
        usos.recordUse("ruta-de-ana")

        // Con otra cuenta no se ve nada de lo de Ana, ni al cerrar la sesión.
        sesiones.save(sesion("beto"))
        assertTrue("beto ve ${usos.uses.value}", usos.uses.value.isEmpty())
        usos.recordUse("ruta-de-beto")
        sesiones.clear()
        assertTrue("sin cuenta se ve ${usos.uses.value}", usos.uses.value.isEmpty())

        // Y al volver, cada una encuentra lo suyo.
        sesiones.save(sesion("ana"))
        assertEquals(setOf("ruta-de-antes", "ruta-de-ana"), usos.uses.value.keys)
        sesiones.save(sesion("beto"))
        assertEquals(setOf("ruta-de-beto"), usos.uses.value.keys)
        sesiones.clear()
    }

    @Test
    fun `al eliminar la cuenta se borra solo lo suyo`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sesiones = SessionStore.get(context)
        val usos = RouteUsageStore.get(context)
        sesiones.clear()

        sesiones.save(sesion("carla"))
        usos.recordUse("ruta-de-carla")
        sesiones.save(sesion("dani"))
        usos.recordUse("ruta-de-dani")

        // Como al borrar la cuenta: primero lo suyo y luego se cierra la sesión.
        AccountPrefs.wipe(context, "dani")
        sesiones.clear()
        val carpeta = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        assertFalse(java.io.File(carpeta, "expresdm_route_usage_dani.xml").exists())

        // Si vuelve a entrar con ese id no encuentra nada; las de otra cuenta siguen.
        sesiones.save(sesion("dani"))
        assertTrue("dani ve ${usos.uses.value}", usos.uses.value.isEmpty())
        sesiones.save(sesion("carla"))
        assertEquals(setOf("ruta-de-carla"), usos.uses.value.keys)
        sesiones.clear()
    }
}
