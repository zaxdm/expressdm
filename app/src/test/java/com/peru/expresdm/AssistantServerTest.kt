package com.peru.expresdm

import com.peru.expresdm.ai.AssistantRepository
import com.peru.expresdm.ai.GeminiClient
import com.peru.expresdm.data.BusRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lo que la app manda al asistente del servidor y lo que entiende de su respuesta. */
@RunWith(RobolectricTestRunner::class)
class AssistantServerTest {

    private fun route(id: String) = BusRoute(
        id = id,
        letter = id.uppercase(),
        name = "Ruta $id",
        origin = "Inicio",
        destination = "Final",
        durationMinutes = 20,
        fare = 1.5,
        schedule = "05:00 - 22:00",
        stops = emptyList(),
        accentIndex = 0
    )

    private val catalog = listOf("a", "b", "c", "d", "e").associateWith { route(it) }

    @Test
    fun `manda la conversacion y donde esta quien pregunta`() {
        val history = listOf(
            GeminiClient.Turn("¿Cómo llego al Mall?", fromUser = true),
            GeminiClient.Turn("Toma la A.", fromUser = false)
        )
        val body = AssistantRepository.serverBody("¿Y de vuelta?", history, -8.11 to -79.03, "es")

        assertEquals("¿Y de vuelta?", body.getString("question"))
        assertEquals("es", body.getString("language"))
        assertEquals(-8.11, body.getDouble("latitude"), 0.0)
        assertEquals(-79.03, body.getDouble("longitude"), 0.0)
        val turns = body.getJSONArray("history")
        assertEquals(2, turns.length())
        assertTrue(turns.getJSONObject(0).getBoolean("mine"))
        assertEquals("Toma la A.", turns.getJSONObject(1).getString("text"))
        assertFalse(turns.getJSONObject(1).getBoolean("mine"))
    }

    @Test
    fun `sin ubicacion no manda coordenadas`() {
        val body = AssistantRepository.serverBody("Hola", emptyList(), null, "es")

        assertFalse(body.has("latitude"))
        assertFalse(body.has("longitude"))
        assertEquals(0, body.getJSONArray("history").length())
    }

    @Test
    fun `lee todas las rutas recomendadas y descarta las que no conoce`() {
        val data = JSONObject("""{"answer":"Te sirven estas","routeIds":["b","x","a","b"],"routeId":"b","source":"reglas"}""")
        val answer = AssistantRepository.parseServerAnswer(data, catalog::get)!!

        assertEquals("Te sirven estas", answer.text)
        assertEquals(listOf("b", "a"), answer.suggestedRoutes.map { it.id })
    }

    @Test
    fun `no muestra mas rutas que las que puede recomendar el servidor`() {
        val data = JSONObject("""{"answer":"Varias","routeIds":["a","b","c","d","e"]}""")

        assertEquals(4, AssistantRepository.parseServerAnswer(data, catalog::get)!!.suggestedRoutes.size)
    }

    @Test
    fun `un servidor antiguo que manda solo routeId sigue funcionando`() {
        val data = JSONObject("""{"answer":"Toma la C","routeId":"c"}""")

        assertEquals(listOf("c"), AssistantRepository.parseServerAnswer(data, catalog::get)!!.suggestedRoutes.map { it.id })
    }

    @Test
    fun `una respuesta de preguntas frecuentes no trae rutas`() {
        val data = JSONObject("""{"answer":"Revisa la ficha de la ruta","routeIds":[],"source":"faq"}""")

        assertTrue(AssistantRepository.parseServerAnswer(data, catalog::get)!!.suggestedRoutes.isEmpty())
    }

    @Test
    fun `sin texto la respuesta no vale`() {
        assertNull(AssistantRepository.parseServerAnswer(JSONObject("""{"answer":"  ","routeIds":["a"]}"""), catalog::get))
    }
}
