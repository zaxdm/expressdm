package com.peru.expresdm

import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.NearbyRoutes
import com.peru.expresdm.data.RouteRepository
import com.peru.expresdm.data.Stop
import com.peru.expresdm.data.UserPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRoutesTest {

    private val plazaDeArmas = UserPosition(-8.1120, -79.0288)

    /** Lima: a más de 500 km de cualquier ruta de Trujillo. */
    private val lima = UserPosition(-12.0464, -77.0428)

    @Test
    fun `la distancia a uno mismo es cero`() {
        assertEquals(0.0, NearbyRoutes.distanceMeters(plazaDeArmas, plazaDeArmas), 0.001)
    }

    @Test
    fun `la distancia entre dos lugares conocidos es realista`() {
        // De la Plaza de Armas a Huanchaco hay unos 10,5 km en línea recta.
        val km = NearbyRoutes.distanceMeters(plazaDeArmas, UserPosition(-8.0797, -79.1187)) / 1_000
        assertTrue("distancia inesperada: $km km", km in 10.0..11.0)
    }

    @Test
    fun `desde la Plaza de Armas hay rutas dentro del radio`() {
        val result = NearbyRoutes.find(plazaDeArmas)

        assertTrue(result.anyWithinRadius)
        assertTrue(result.routes.isNotEmpty())
        assertTrue(result.routes.all { it.distanceMeters <= NearbyRoutes.RADIUS_METERS })
    }

    @Test
    fun `los resultados van de la ruta mas cercana a la mas lejana`() {
        val distances = NearbyRoutes.find(plazaDeArmas).routes.map { it.distanceMeters }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun `devuelve todas las rutas dentro del radio y no solo las primeras`() {
        // Desde el centro las seis rutas caen dentro del radio: la hoja las pagina de cinco en cinco.
        val inside = RouteRepository.all().count {
            NearbyRoutes.measure(it, plazaDeArmas).distanceMeters <= NearbyRoutes.RADIUS_METERS
        }
        assertTrue(inside > NearbyRoutes.PAGE_SIZE)
        assertEquals(inside, NearbyRoutes.find(plazaDeArmas).routes.size)
    }

    @Test
    fun `las paginas van de cinco en cinco`() {
        val items = (1..14).toList()

        assertEquals(3, NearbyRoutes.pageCount(items.size))
        assertEquals(listOf(1, 2, 3, 4, 5), NearbyRoutes.page(items, 0))
        assertEquals(listOf(6, 7, 8, 9, 10), NearbyRoutes.page(items, 1))
        assertEquals(listOf(11, 12, 13, 14), NearbyRoutes.page(items, 2))
    }

    @Test
    fun `las flechas solo salen en la lista de cercanas y con mas de cinco`() {
        assertTrue(NearbyRoutes.showsPager(nearbyList = true, total = 125))
        assertFalse(NearbyRoutes.showsPager(nearbyList = true, total = 5))
        // En "Cómo llegar", en una búsqueda o en "Todas", nunca.
        assertFalse(NearbyRoutes.showsPager(nearbyList = false, total = 125))
    }

    @Test
    fun `una pagina que ya no existe da la ultima`() {
        // Al moverse el usuario pueden quedar menos rutas que antes.
        assertEquals(listOf(6, 7), NearbyRoutes.page((1..7).toList(), 4))
        assertEquals(listOf(1, 2), NearbyRoutes.page(listOf(1, 2), -1))
        assertEquals(1, NearbyRoutes.pageCount(0))
        assertTrue(NearbyRoutes.page(emptyList<Int>(), 0).isEmpty())
    }

    @Test
    fun `elige la parada mas cercana de cada ruta`() {
        val california = RouteRepository.byId("california-porvenir")!!
        val nearby = NearbyRoutes.measure(california, plazaDeArmas)

        assertEquals("Plaza de Armas", nearby.nearestStop.name)
        assertEquals(0.0, nearby.distanceMeters, 1.0)
    }

    @Test
    fun `en la calle por la que pasa el micro se sube ahi mismo y no en el paradero`() {
        // Dos referencias a unos 2 km; el usuario está a mitad de la calle que las une.
        val route = RouteRepository.all().first().copy(
            id = "recta",
            stops = listOf(Stop("Inicio", -8.1000, -79.0300), Stop("Final", -8.1180, -79.0300)),
            path = null
        )
        val nearby = NearbyRoutes.measure(route, UserPosition(-8.1090, -79.0300))

        assertEquals(0.0, nearby.distanceMeters, 1.0)
        assertFalse(nearby.boardsAtStop)
        assertEquals(-8.1090, nearby.boarding.latitude, 0.0001)
        assertTrue(NearbyRoutes.find(UserPosition(-8.1090, -79.0300), listOf(route)).anyWithinRadius)
    }

    @Test
    fun `lejos de todo muestra igualmente las rutas mas proximas`() {
        val result = NearbyRoutes.find(lima)

        assertFalse(result.anyWithinRadius)
        assertEquals(NearbyRoutes.FALLBACK_RESULTS, result.routes.size)
        val distances = result.routes.map { it.distanceMeters }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun `sin rutas no devuelve nada`() {
        val result = NearbyRoutes.find(plazaDeArmas, routes = emptyList())
        assertTrue(result.routes.isEmpty())
        assertFalse(result.anyWithinRadius)
    }

    @Test
    fun `ignora las rutas sin paradas en lugar de fallar`() {
        val empty = RouteRepository.all().first().copy(id = "vacia", stops = emptyList<Stop>())
        val routes: List<BusRoute> = listOf(empty) + RouteRepository.all()

        assertTrue(NearbyRoutes.find(plazaDeArmas, routes).routes.none { it.route.id == "vacia" })
    }

    @Test
    fun `los minutos a pie salen de la distancia`() {
        val route = RouteRepository.all().first()
        val stop = route.stops.first()

        assertEquals(5, NearbyRoutes.measure(route, plazaDeArmas).copy(distanceMeters = 400.0).walkingMinutes)
        // Aunque la parada esté pegada, nunca decimos "0 min".
        assertEquals(1, NearbyRoutes.measure(route, UserPosition(stop.latitude, stop.longitude)).walkingMinutes)
    }
}
