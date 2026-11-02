package com.peru.expresdm.data

import java.text.Normalizer

/**
 * Una línea en la lista de "¿De qué micro?" al crear un reporte.
 *
 * [routeId] es la ruta que se manda con el reporte (la ida de la línea), o
 * `null` en "No sé / ninguna". [label] es lo que se lee y [toString] lo
 * devuelve para que la lista y la casilla lo enseñen tal cual.
 */
data class RouteChoice(val routeId: String?, val label: String, val searchText: String) {
    override fun toString(): String = label
}

/**
 * Arma la lista de micros para un reporte: una fila por línea (la ida y la
 * vuelta son la misma), ordenadas como se leen los códigos (C-2 antes que
 * C-10) y que se puede filtrar escribiendo el código o un lugar.
 *
 * Es lógica pura, sin Android, para poder probarla directamente.
 */
object RouteChoices {

    /** El código oficial de una línea: «C-01», «M-23». La letra y su número. */
    data class Code(val prefix: Char, val number: Int, val text: String)

    // C1 puede ser la letra de una línea; M-13 es su código oficial.
    private val CODE_PATTERNS = listOf(
        Regex("\\b([cmCM])\\s*-\\s*(\\d{1,3})\\b"),
        Regex("\\b([cmCM])\\s+(\\d{1,3})\\b"),
        Regex("\\b([cmCM])(\\d{2,3})\\b")
    )

    /** El código que trae el nombre o la descripción de la ruta, si trae uno. */
    fun codeOf(route: BusRoute): Code? =
        codeIn(route.name) ?: codeIn(route.description) ?: codeIn(route.letter)

    private fun codeIn(text: String): Code? {
        for (pattern in CODE_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val prefix = match.groupValues[1].uppercase().first()
            val digits = match.groupValues[2]
            return Code(prefix, digits.toInt(), "$prefix-${digits.padStart(2, '0')}")
        }
        return null
    }

    /** Una fila por línea, en orden, con la ida como ruta que se manda. */
    fun build(routes: List<BusRoute>): List<RouteChoice> = routes
        .groupBy { RouteDirections.lineKey(it) }
        .values
        .map { line -> line.first() to line }
        .sortedWith { (a, _), (b, _) -> compare(a, b) }
        .map { (ida, line) -> choiceFor(ida, line) }

    private fun choiceFor(ida: BusRoute, line: List<BusRoute>): RouteChoice {
        val code = codeOf(ida)
        val name = nameWithoutCode(ida.name).ifBlank { ida.vehicleName.ifBlank { ida.name } }
        val places = listOf(ida.origin, ida.destination).filter { it.isNotBlank() }.joinToString(" ↔ ")
        val label = listOfNotNull(code?.text, name.takeIf { it.isNotBlank() }, places.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        // Se busca por el código, la empresa y los barrios de los dos sentidos.
        val search = buildList {
            add(label)
            add(ida.vehicleName)
            add(ida.letter)
            line.forEach { add(it.origin); add(it.destination) }
            code?.let { add("${it.prefix}${it.number}"); add("${it.prefix}${it.text.substringAfter('-')}") }
        }.joinToString(" ")
        return RouteChoice(ida.id, label, normalize(search))
    }

    /** «Virgen de la Puerta B (M-23)» → «Virgen de la Puerta B». */
    private fun nameWithoutCode(name: String): String =
        name.replace(Regex("\\(\\s*[cmCM]\\s*-?\\s*\\d{1,3}\\s*\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Primero las que tienen código, por su letra y su número; luego el resto, por nombre. */
    private fun compare(a: BusRoute, b: BusRoute): Int {
        val codeA = codeOf(a)
        val codeB = codeOf(b)
        return when {
            codeA != null && codeB != null -> compareValuesBy(codeA, codeB, { it.prefix }, { it.number })
                .takeIf { it != 0 } ?: naturalCompare(a.name, b.name)
            codeA != null -> -1
            codeB != null -> 1
            else -> naturalCompare(a.name, b.name)
        }
    }

    /** Compara textos leyendo los números como números: «Ruta 2» antes que «Ruta 10». */
    fun naturalCompare(a: String, b: String): Int {
        val partsA = chunks(normalize(a))
        val partsB = chunks(normalize(b))
        for (i in 0 until minOf(partsA.size, partsB.size)) {
            val x = partsA[i]
            val y = partsB[i]
            val result = if (x.first().isDigit() && y.first().isDigit()) {
                compareValues(x.toBigInteger(), y.toBigInteger())
            } else {
                x.compareTo(y)
            }
            if (result != 0) return result
        }
        return partsA.size - partsB.size
    }

    private fun chunks(text: String): List<String> = Regex("\\d+|\\D+").findAll(text).map { it.value }.toList()

    /**
     * Si [choice] sirve para lo escrito: cada palabra tiene que estar en el
     * código, la empresa o algún barrio. «m23», «M-23» y «m 23» dan lo mismo.
     */
    fun matches(choice: RouteChoice, query: String): Boolean {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        if (words.all { it in choice.searchText }) return true
        val compact = words.joinToString("")
        return choice.searchText.split(' ').any { it == compact }
    }

    /** Sin tildes, en minúsculas y solo letras y números. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
