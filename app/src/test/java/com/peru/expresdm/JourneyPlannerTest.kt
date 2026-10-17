package com.peru.expresdm

import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.GeoCoordinate
import com.peru.expresdm.data.JourneyLeg
import com.peru.expresdm.data.JourneyPlanner
import com.peru.expresdm.data.RidePoint
import com.peru.expresdm.data.Stop
import com.peru.expresdm.data.UserPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class JourneyPlannerTest {

    private val baseLat = -8.1100
    private val baseLon = -79.0300
    private val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(baseLat))

    /** Un punto a tantos metros al norte y al este del origen del escenario. */
    private fun punto(norte: Double, este: Double) =
        GeoCoordinate(baseLat + norte / 110_540.0, baseLon + este / metersPerDegreeLon)

    private fun parada(nombre: String, norte: Double, este: Double): Stop {
        val p = punto(norte, este)
        return Stop(nombre, p.latitude, p.longitude)
    }

    private fun posicion(norte: Double, este: Double): UserPosition {
        val p = punto(norte, este)
        return UserPosition(p.latitude, p.longitude)
    }

    /** Una línea recta de sur a norte, con paradas cada kilómetro. */
    private val lineaNorte = BusRoute(
        id = "norte", letter = "N", name = "Norte", origin = "Sur", destination = "Norte",
        durationMinutes = 40, fare = 2.0, schedule = "", accentIndex = 0,
        stops = listOf(
            parada("Sur", 0.0, 0.0),
            parada("Centro", 4_000.0, 0.0),
            parada("Norte", 8_000.0, 0.0)
        )
    )

    /** Otra línea, de oeste a este, que cruza a la anterior en el Centro. */
    private val lineaEste = BusRoute(
        id = "este", letter = "E", name = "Este", origin = "Centro", destination = "Playa",
        durationMinutes = 30, fare = 1.5, schedule = "", accentIndex = 1,
        stops = listOf(
            parada("Centro Este", 4_000.0, 200.0),
            parada("Mercado", 4_000.0, 3_000.0),
            parada("Playa", 4_000.0, 6_000.0)
        )
    )

    private val catalogo = listOf(lineaNorte, lineaEste)

    @Test
    fun `una ruta de un solo sentido no permite viajar al reves`() {
        val viajes = JourneyPlanner.plan(posicion(8_000.0, 0.0), punto(0.0, 0.0),
            listOf(lineaNorte.copy(bidirectional = false)))
        assertTrue(viajes.isEmpty())
        val regreso = JourneyPlanner.plan(posicion(8_000.0, 0.0), punto(0.0, 0.0), listOf(lineaNorte))
        assertTrue(regreso.any { it.rides.size == 1 })
    }

    /** La ida baja por el oeste y dobla hasta el paradero final; la vuelta sale de ahí por otra calle. */
    private val ida = lineaNorte.copy(
        id = "ida", name = "Circuito", bidirectional = false,
        stops = listOf(parada("Arriba", 8_000.0, 0.0), parada("Esquina", 0.0, 0.0), parada("Paradero", 0.0, 1_500.0))
    )
    private val vuelta = lineaNorte.copy(
        id = "vuelta", name = "Circuito", bidirectional = false,
        stops = listOf(parada("Paradero", 0.0, 1_500.0), parada("Arriba Este", 8_000.0, 1_500.0))
    )

    @Test
    fun `cerca del paradero final se sube al micro que llega y se sigue en el de vuelta`() {
        // La vuelta no pasa por aquí: sale del paradero, a 1,2 km. La ida sí, ya terminando.
        val viaje = JourneyPlanner.plan(posicion(0.0, 300.0), punto(7_000.0, 1_500.0), listOf(ida, vuelta))
            .single { !it.walkingOnly }
        val tramo = viaje.rides.single()

        assertEquals("vuelta", tramo.route.id)
        // Sube donde está, no caminando hasta el paradero.
        assertTrue("camina ${viaje.walkMeters} m", viaje.walkMeters < 100.0)
        assertEquals(punto(0.0, 300.0).longitude, tramo.from.longitude, 1e-4)
        // 1,2 km hasta el paradero y 7 km de vuelta, a 8 km en 40 min: unos 41 min.
        assertTrue("dijo ${tramo.minutes} min", tramo.minutes in 39..43)
        // Un solo pasaje: es el mismo micro.
        assertEquals(2.0, viaje.fare, 0.001)
    }

    @Test
    fun `una linea circular se toma tambien pasando por su paradero`() {
        // Un cuadrado de un solo sentido que empieza y acaba en el mismo sitio.
        val circular = lineaNorte.copy(
            id = "circular", bidirectional = false,
            stops = listOf(
                parada("Inicio", 0.0, 0.0), parada("Norte", 3_000.0, 0.0), parada("Noreste", 3_000.0, 3_000.0),
                parada("Este", 0.0, 3_000.0), parada("Inicio", 0.0, 0.0)
            )
        )
        // De poco antes del paradero a poco después: hay que pasar por él.
        val tramo = JourneyPlanner.plan(posicion(0.0, 1_600.0), punto(1_600.0, 0.0), listOf(circular))
            .first { !it.walkingOnly }.rides.single()

        assertEquals("circular", tramo.route.id)
        assertTrue("dijo ${tramo.minutes} min", tramo.minutes in 9..12)
    }

    @Test
    fun `busca otro enlace si el par mas cercano no sirve para cambiar`() {
        val primera = lineaNorte.copy(stops = listOf(
            parada("Inicio", 0.0, 0.0), parada("Cambio", 4_000.0, 0.0)))
        // De un solo sentido y con el paradero coincidente al final: desde el
        // origen no lleva sola al destino, así que hay que enlazar en otro sitio.
        val segunda = lineaEste.copy(bidirectional = false, stops = listOf(
            parada("Cambio cercano", 4_000.0, 200.0),
            parada("Destino", 4_000.0, 6_000.0),
            parada("Inicio coincidente", 0.0, 0.0)))
        val viajes = JourneyPlanner.plan(posicion(0.0, 0.0), punto(4_000.0, 6_000.0), listOf(primera, segunda))
        val cambio = viajes.first { it.transfers == 1 }
        assertEquals("Cambio", cambio.rides.first().to.name)
        assertEquals("Cambio cercano", cambio.rides.last().from.name)
        assertTrue(cambio.legs.any { it is JourneyLeg.Walk })
    }

    @Test
    fun `si el destino esta cerca propone ir caminando`() {
        val viajes = JourneyPlanner.plan(posicion(0.0, 0.0), punto(600.0, 0.0), catalogo)
        val primero = viajes.first()

        assertTrue("no propuso caminar: $viajes", primero.walkingOnly)
        assertEquals(0.0, primero.fare, 0.001)
        assertTrue("dijo ${primero.totalMinutes} min", primero.totalMinutes in 6..10)
    }

    @Test
    fun `un solo micro cuando la linea pasa por origen y destino`() {
        val viajes = JourneyPlanner.plan(posicion(100.0, 0.0), punto(7_900.0, 0.0), catalogo)
        val directo = viajes.first { !it.walkingOnly }

        assertEquals(0, directo.transfers)
        assertEquals("norte", directo.rides.single().route.id)
        assertEquals("Sur", directo.rides.single().from.name)
        assertEquals("Norte", directo.rides.single().to.name)
        assertEquals(2.0, directo.fare, 0.001)
    }

    @Test
    fun `dos micros cuando hay que cruzar la ciudad`() {
        // Desde el sur hasta la playa: la línea Norte no llega, hay que cambiar.
        val viajes = JourneyPlanner.plan(posicion(0.0, 0.0), punto(4_000.0, 5_900.0), catalogo)
        val conCambio = viajes.first { it.transfers == 1 }

        assertEquals(listOf("norte", "este"), conCambio.rides.map { it.route.id })
        assertEquals("Centro", conCambio.rides.first().to.name)
        assertEquals("Centro Este", conCambio.rides.last().from.name)
        // Se paga el pasaje de los dos micros.
        assertEquals(3.5, conCambio.fare, 0.001)
    }

    @Test
    fun `el viaje empieza y termina caminando`() {
        // A 300 m de la línea por los dos lados: hay que caminar hasta ella y desde ella.
        val viaje = JourneyPlanner.plan(posicion(1_000.0, 300.0), punto(7_000.0, 300.0), catalogo)
            .first { !it.walkingOnly }

        assertTrue("primer tramo: ${viaje.legs.first()}", viaje.legs.first() is JourneyLeg.Walk)
        assertTrue("ultimo tramo: ${viaje.legs.last()}", viaje.legs.last() is JourneyLeg.Walk)
    }

    @Test
    fun `se sube y se baja en cualquier punto del recorrido, no solo en las paradas`() {
        // Origen y destino entre paradas (que están cada 4 km), junto a la línea.
        val viajes = JourneyPlanner.plan(posicion(1_500.0, 100.0), punto(6_500.0, 100.0), listOf(lineaNorte))
        val tramo = viajes.single().rides.single()

        assertEquals("norte", tramo.route.id)
        assertEquals(RidePoint.ON_ROUTE, tramo.fromKind)
        // Sube a la altura de donde está, no en la parada del kilómetro 0.
        assertEquals(punto(1_500.0, 0.0).latitude, tramo.from.latitude, 1e-4)
        assertEquals(punto(6_500.0, 0.0).latitude, tramo.to.latitude, 1e-4)
        // El trazado del tramo empieza y acaba en esos puntos.
        assertEquals(tramo.from.latitude, tramo.line.first().latitude, 1e-9)
        assertEquals(tramo.to.latitude, tramo.line.last().latitude, 1e-9)
        // Cinco de los ocho kilómetros de una ruta de 40 min: unos 25 min.
        assertTrue("dijo ${tramo.minutes} min", tramo.minutes in 24..26)
        // Solo se camina hasta la línea y desde ella: unos 100 m cada vez.
        assertTrue("camina ${viajes.single().walkMeters} m", viajes.single().walkMeters in 150.0..250.0)
    }

    @Test
    fun `junto a una parada conocida se usa la parada`() {
        val tramo = JourneyPlanner.plan(posicion(40.0, 30.0), punto(4_030.0, 30.0), listOf(lineaNorte))
            .first { !it.walkingOnly }.rides.single()

        assertEquals(RidePoint.STOP, tramo.fromKind)
        assertEquals("Sur", tramo.from.name)
        assertEquals("Centro", tramo.to.name)
    }

    @Test
    fun `en la calle de la ruta no obliga a ir hasta la parada aunque este cerca`() {
        // A 150 m de la parada «Sur», pero a 30 m de la calle por la que pasa el micro.
        val viaje = JourneyPlanner.plan(posicion(150.0, 30.0), punto(4_100.0, 30.0), listOf(lineaNorte))
            .first { !it.walkingOnly }
        val tramo = viaje.rides.single()

        assertEquals(RidePoint.NEAR_STOP, tramo.fromKind)
        assertEquals("Sur", tramo.from.name)
        assertEquals(punto(150.0, 0.0).latitude, tramo.from.latitude, 1e-4)
        assertTrue("camina ${viaje.walkMeters} m", viaje.walkMeters < 100.0)
    }

    @Test
    fun `cambia de micro donde los recorridos se cruzan aunque no haya paradas`() {
        // Dos líneas que se cruzan lejos de sus paradas.
        val vertical = lineaNorte.copy(stops = listOf(parada("Abajo", 0.0, 0.0), parada("Arriba", 8_000.0, 0.0)))
        val horizontal = lineaEste.copy(stops = listOf(parada("Oeste", 4_000.0, -3_000.0), parada("Este", 4_000.0, 6_000.0)))
        val viajes = JourneyPlanner.plan(posicion(500.0, 0.0), punto(4_000.0, 5_000.0), listOf(vertical, horizontal))
        val conCambio = viajes.first { it.transfers == 1 }

        assertEquals(listOf("norte", "este"), conCambio.rides.map { it.route.id })
        // Se baja del primero a la altura del cruce, en el kilómetro 4.
        assertEquals(punto(4_000.0, 0.0).latitude, conCambio.rides.first().to.latitude, 3e-3)
    }

    @Test
    fun `no propone cambiar de micro para acabar en uno que ya llega solo`() {
        // La línea Norte lleva sola; la Este solo serviría para dar un rodeo.
        val viajes = JourneyPlanner.plan(posicion(4_000.0, 300.0), punto(7_900.0, 0.0), catalogo)
        assertTrue("propuso $viajes", viajes.none { it.transfers > 0 })
        assertEquals(listOf("norte"), viajes.first { !it.walkingOnly }.key)
    }

    @Test
    fun `si ningun micro pasa cerca del destino propone el que deja mas cerca`() {
        // El destino queda a 1,5 km de la línea: lejos para lo normal, pero es lo que hay.
        val viajes = JourneyPlanner.plan(posicion(0.0, 0.0), punto(6_000.0, 1_500.0), listOf(lineaNorte))
        val viaje = viajes.single()

        assertEquals(listOf("norte"), viaje.key)
        assertTrue("camina ${viaje.walkMeters} m", viaje.walkMeters in 1_400.0..1_600.0)
        // Más lejos todavía ya no se propone nada.
        assertTrue(JourneyPlanner.plan(posicion(0.0, 0.0), punto(6_000.0, 2_500.0), listOf(lineaNorte)).isEmpty())
    }

    @Test
    fun `no propone micros que alejan del destino`() {
        // Destino al sur del usuario: subirse hacia el norte sería absurdo.
        val viajes = JourneyPlanner.plan(posicion(4_000.0, 0.0), punto(100.0, 0.0), catalogo)
        viajes.filterNot { it.walkingOnly }.forEach { viaje ->
            viaje.rides.forEach { tramo ->
                assertTrue(
                    "el tramo ${tramo.from.name} -> ${tramo.to.name} aleja del destino",
                    tramo.to.name != "Norte"
                )
            }
        }
    }

    @Test
    fun `sin micros cerca no inventa viajes`() {
        // A 40 km al este no pasa ninguna línea del catálogo.
        val viajes = JourneyPlanner.plan(posicion(0.0, 40_000.0), punto(0.0, 45_000.0), catalogo)
        assertTrue("propuso $viajes", viajes.isEmpty())
    }

    @Test
    fun `el tiempo del tramo es parte del tiempo de la ruta`() {
        val viaje = JourneyPlanner.plan(posicion(0.0, 0.0), punto(4_100.0, 0.0), catalogo)
            .first { !it.walkingOnly }
        val tramo = viaje.rides.first()

        // Medio recorrido de una ruta de 40 min: unos 20 min.
        assertTrue("dijo ${tramo.minutes} min", tramo.minutes in 18..22)
    }

    @Test
    fun `el trazado del tramo va de una parada a la otra`() {
        val linea = JourneyPlanner.segmentLine(lineaNorte, lineaNorte.stops[0], lineaNorte.stops[1])

        assertEquals(2, linea.size)
        assertEquals(lineaNorte.stops[0].latitude, linea.first().latitude, 1e-9)
        assertEquals(lineaNorte.stops[1].latitude, linea.last().latitude, 1e-9)
    }

    @Test
    fun `con recorrido grabado el trazado del tramo se recorta de el`() {
        val recorrido = (0..80).map { punto(it * 100.0, 0.0) }
        val conRecorrido = lineaNorte.copy(path = recorrido)

        val linea = JourneyPlanner.segmentLine(conRecorrido, conRecorrido.stops[0], conRecorrido.stops[1])

        // Del kilómetro 0 al 4: unos 41 puntos de los 81 del recorrido.
        assertTrue("recorto ${linea.size} puntos", linea.size in 35..45)
    }
}
