package com.peru.expresdm

import com.peru.expresdm.data.ArrivalSms
import com.peru.expresdm.data.SmsContact
import com.peru.expresdm.tracking.ArrivalSmsSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArrivalSmsTest {

    @Test
    fun limpiaElNumero() {
        assertEquals("987654321", ArrivalSms.cleanNumber(" 987 654 321 "))
        assertEquals("+51987654321", ArrivalSms.cleanNumber("+51 987-654-321"))
        assertNull(ArrivalSms.cleanNumber("12"))
        assertNull(ArrivalSms.cleanNumber("hola"))
    }

    @Test
    fun enseñaElCelularPorPartes() {
        assertEquals("987 654 321", ArrivalSms.displayNumber("987654321"))
        assertEquals("+51987654321", ArrivalSms.displayNumber("+51987654321"))
    }

    @Test
    fun elTextoCabeEnUnSmsYNoLlevaTildes() {
        val text = ArrivalSms.message(
            "Ángel Pérez",
            "Paradero Óvalo Larco con Avenida Húsares de Junín, frente a la Universidad Privada Antenor Orrego",
            "21:35",
            -8.112345,
            -79.028812
        )
        assertTrue(text, text.length <= ArrivalSms.MAX_LENGTH)
        assertTrue(text, text.startsWith("Subo: Angel llego a Paradero Ovalo Larco"))
        assertTrue(text, text.endsWith("a las 21:35. https://maps.google.com/?q=-8.11235,-79.02881"))
        assertTrue(text, text.none { it in "áéíóúÁÉÍÓÚ…" })
    }

    @Test
    fun sinNombreDiceYaLlegue() {
        assertEquals("Subo: ya llegue a Plaza de Armas a las 08:05.", ArrivalSms.message(null, "Plaza de Armas", "08:05", null, null))
    }

    @Test
    fun laEñeSeQueda() {
        assertEquals("Peña", ArrivalSms.plain("Peña"))
    }

    @Test
    fun whatsappLlevaElCodigoDePeru() {
        val uri = ArrivalSmsSender.whatsappUri(SmsContact("Mamá", "987654321"), "ya llegue")
        assertEquals("https://wa.me/51987654321?text=ya%20llegue", uri.toString())
    }
}
