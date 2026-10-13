package com.peru.expresdm

import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.ProfileStats
import com.peru.expresdm.data.Stop
import com.peru.expresdm.ui.Initials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El resumen del perfil: rutas guardadas o grabadas, alertas publicadas y rutas usadas. */
class ProfileStatsTest {

    private val now = 1_700_000_000_000L

    private fun route(id: String, mine: Boolean = false) = BusRoute(
        id = id,
        letter = id.take(1).uppercase(),
        name = "Ruta $id",
        origin = "A",
        destination = "B",
        durationMinutes = 30,
        fare = 1.5,
        schedule = "5:00 - 22:00",
        stops = listOf(Stop("Parada", -8.11, -79.02)),
        accentIndex = 0,
        createdByUser = mine
    )

    @Test
    fun `cuenta solo las rutas que grabo el usuario`() {
        val routes = listOf(route("a"), route("b", mine = true), route("c", mine = true))
        val stats = ProfileStats.of(routes, emptySet(), 0, emptyMap())
        assertEquals(2, stats.recordedRoutes)
    }

    @Test
    fun `cuenta las rutas guardadas que siguen en el catalogo`() {
        val stats = ProfileStats.of(listOf(route("a"), route("b")), setOf("a", "b", "borrada"), 0, emptyMap())
        assertEquals(2, stats.savedRoutes)
    }

    @Test
    fun `las alertas son todas las publicadas, no solo las vigentes`() {
        val stats = ProfileStats.of(emptyList(), emptySet(), 7, emptyMap())
        assertEquals(7, stats.publishedAlerts)
    }

    @Test
    fun `una ruta borrada deja de contar como usada`() {
        val routes = listOf(route("a"))
        val uses = mapOf("a" to 4, "borrada" to 9)
        val stats = ProfileStats.of(routes, emptySet(), 0, uses)
        assertEquals(1, stats.usedRoutes)
    }

    @Test
    fun `sin nada hecho el resumen esta vacio`() {
        assertTrue(ProfileStats.of(listOf(route("a")), emptySet(), 0, emptyMap()).isEmpty)
        assertFalse(ProfileStats.of(listOf(route("a", mine = true)), emptySet(), 0, emptyMap()).isEmpty)
        assertFalse(ProfileStats.of(listOf(route("a")), setOf("a"), 0, emptyMap()).isEmpty)
        assertFalse(ProfileStats.of(listOf(route("a")), emptySet(), 1, emptyMap()).isEmpty)
    }

    @Test
    fun `las iniciales salen del nombre`() {
        assertEquals("AM", Initials.of("Ana Maria"))
        assertEquals("A", Initials.of("ana"))
        assertEquals("AM", Initials.of("  ana   maria  quispe "))
    }

    @Test
    fun `sin nombre util se usa el signo de interrogacion`() {
        assertEquals(Initials.PLACEHOLDER, Initials.of(null))
        assertEquals(Initials.PLACEHOLDER, Initials.of("   "))
        assertEquals(Initials.PLACEHOLDER, Initials.of("!!!"))
    }
}
