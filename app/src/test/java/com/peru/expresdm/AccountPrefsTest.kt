package com.peru.expresdm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.peru.expresdm.data.RouteUsageStore
import com.peru.expresdm.data.net.Session
import com.peru.expresdm.data.net.SessionStore
import org.junit.Assert.assertEquals
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
}
