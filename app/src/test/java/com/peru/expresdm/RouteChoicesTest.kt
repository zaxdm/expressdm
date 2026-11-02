package com.peru.expresdm

import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.RouteChoices
import com.peru.expresdm.data.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChoicesTest {

    private fun route(id: String, name: String, origin: String, destination: String, letter: String = "A") = BusRoute(
        id = id, letter = letter, name = name, origin = origin, destination = destination,
        durationMinutes = 30, fare = 1.5, schedule = "", accentIndex = 0,
        stops = listOf(Stop(origin, -8.1, -79.0), Stop(destination, -8.12, -79.03))
    )

    private val c10ida = route("c10-ida", "Santa Rosa A (C-10)", "La Esperanza", "Centro")
    private val c10vuelta = route("c10-vuelta", "Santa Rosa A (C-10)", "Centro", "La Esperanza")
    private val c2 = route("c2", "Nuevos Girasoles B (C-02)", "El Porvenir", "Buenos Aires", letter = "B")
    private val m23 = route("m23", "Virgen de la Puerta B (M-23)", "Alto Trujillo", "Vista Alegre", letter = "B")
    private val sinCodigo = route("sin", "California", "Huanchaco", "Centro Cívico")

    private val choices = RouteChoices.build(listOf(m23, sinCodigo, c10ida, c10vuelta, c2))

    @Test
    fun `una fila por linea con la ida como ruta`() {
        assertEquals(4, choices.size)
        assertEquals("c10-ida", choices.single { it.label.startsWith("C-10") }.routeId)
    }

    @Test
    fun `ordena por codigo leyendo el numero y deja al final las que no tienen`() {
        assertEquals(listOf("c2", "c10-ida", "m23", "sin"), choices.map { it.routeId })
    }

    @Test
    fun `la fila dice codigo, empresa y los dos barrios`() {
        assertEquals("M-23 · Virgen de la Puerta B · Alto Trujillo ↔ Vista Alegre", choices[2].label)
        assertEquals("California · Huanchaco ↔ Centro Cívico", choices[3].label)
    }

    @Test
    fun `se filtra por codigo escrito de cualquier forma`() {
        listOf("m23", "M-23", "m 23").forEach { query ->
            assertEquals(query, listOf("m23"), choices.filter { RouteChoices.matches(it, query) }.map { it.routeId })
        }
    }

    @Test
    fun `se filtra por barrio sin fijarse en tildes`() {
        assertTrue(RouteChoices.matches(choices[3], "centro civico"))
        assertTrue(RouteChoices.matches(choices[1], "esperanza"))
        assertFalse(RouteChoices.matches(choices[2], "huanchaco"))
        assertTrue(RouteChoices.matches(choices[2], ""))
    }

    @Test
    fun `el orden natural lee los numeros como numeros`() {
        assertTrue(RouteChoices.naturalCompare("Ruta 2", "Ruta 10") < 0)
        assertTrue(RouteChoices.naturalCompare("Ruta 10", "Ruta 9") > 0)
        assertEquals(0, RouteChoices.naturalCompare("Ruta 3", "ruta 3"))
    }
}
