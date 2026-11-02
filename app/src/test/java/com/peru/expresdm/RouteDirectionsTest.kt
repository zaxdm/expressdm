package com.peru.expresdm

import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.RouteDirections
import com.peru.expresdm.data.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteDirectionsTest {

    private fun route(
        id: String,
        name: String,
        origin: String,
        destination: String,
        letter: String = "B",
        from: Pair<Double, Double> = -8.10 to -79.00,
        to: Pair<Double, Double> = -8.12 to -79.03
    ) = BusRoute(
        id = id, letter = letter, name = name, origin = origin, destination = destination,
        durationMinutes = 30, fare = 1.5, schedule = "", accentIndex = 0,
        stops = listOf(Stop(origin, from.first, from.second), Stop(destination, to.first, to.second))
    )

    private val ida = route("ida", "Virgen de la Puerta B (M-23)", "Alto Trujillo", "Buenos Aires")
    private val vuelta = route(
        "vuelta", "Virgen de la Puerta B (M-23)", "Buenos Aires", "Alto Trujillo",
        from = -8.12 to -79.03, to = -8.10 to -79.00
    )
    private val otra = route("otra", "California A", "El Porvenir", "Huanchaco", letter = "A")

    @Test
    fun `la primera de la linea es la ida y su pareja la vuelta`() {
        val routes = listOf(otra, ida, vuelta)

        val deIda = RouteDirections.of(ida, routes)!!
        assertEquals(RouteDirections.Way.IDA, deIda.way)
        assertEquals("vuelta", deIda.partner.id)

        val deVuelta = RouteDirections.of(vuelta, routes)!!
        assertEquals(RouteDirections.Way.VUELTA, deVuelta.way)
        assertEquals("ida", deVuelta.partner.id)
    }

    @Test
    fun `una linea con un solo recorrido no tiene pareja`() {
        assertNull(RouteDirections.of(otra, listOf(otra, ida, vuelta)))
    }

    @Test
    fun `el nombre se compara sin mayusculas ni signos`() {
        val escrita = vuelta.copy(name = "virgen de la puerta  b (m-23).")
        assertEquals("vuelta", RouteDirections.of(ida, listOf(ida, escrita))!!.partner.id)
    }

    @Test
    fun `la letra con corazon es otro ramal, no la vuelta`() {
        // C1♡ es un ramal oficial propio: no se junta con C1 aunque vaya al revés.
        val corazon = vuelta.copy(id = "corazon", letter = "${vuelta.letter}♡", name = "${vuelta.name}♡")
        assertNull(RouteDirections.of(ida, listOf(ida, corazon)))
    }

    @Test
    fun `la misma empresa con otra letra es otra linea`() {
        val otraLetra = vuelta.copy(id = "c", letter = "C")
        assertNull(RouteDirections.of(ida, listOf(ida, otraLetra)))
    }

    @Test
    fun `con varias candidatas elige la que sale de donde esta llega`() {
        val variante = route(
            "variante", ida.name, "Centro", "Florencia de Mora",
            from = -8.20 to -79.10, to = -8.25 to -79.15
        )
        // Sin nombre que coincida, decide el mapa: la que empieza junto al final de la ida.
        val sinNombre = vuelta.copy(origin = "Av. Larco")
        assertEquals("vuelta", RouteDirections.of(ida, listOf(ida, variante, sinNombre))!!.partner.id)
    }
}
