package com.peru.expresdm.data

import java.text.Normalizer
import java.util.Locale

/** La persona a la que se le avisa por SMS al llegar. */
data class SmsContact(val name: String, val number: String)

/**
 * El aviso por SMS al llegar (Premium): cuando el usuario llega a su parada,
 * la app manda un SMS desde su propia línea a la persona que eligió. Sirve
 * justo cuando no tiene datos: el SMS no necesita internet, solo señal y
 * saldo (o SMS en su plan).
 *
 * Aquí está lo que no depende de Android: limpiar el número y armar el texto.
 */
object ArrivalSms {

    /**
     * Un SMS normal. Más largo, el operador lo parte en dos y lo cobra doble;
     * por eso el texto va sin tildes: una «ó» lo pasaría a otro alfabeto con
     * sitio para solo 70 letras.
     */
    const val MAX_LENGTH = 160

    /**
     * El número limpio para mandar el SMS, o `null` si no parece un número.
     * Un celular peruano de 9 dígitos se queda tal cual: así sale como un SMS
     * nacional, que es lo que cubren los planes.
     */
    fun cleanNumber(raw: String): String? {
        val plus = raw.trim().startsWith("+")
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 6 || digits.length > 15) return null
        return if (plus) "+$digits" else digits
    }

    /** El número como se lee: «987 654 321». */
    fun displayNumber(number: String): String =
        if (number.length == 9 && number.all { it.isDigit() }) number.chunked(3).joinToString(" ") else number

    /**
     * El texto del SMS: quién llegó, a dónde, a qué hora y el enlace al mapa,
     * que se abre cuando quien lo recibe tenga internet.
     */
    fun message(
        userName: String?,
        placeName: String,
        time: String,
        latitude: Double?,
        longitude: Double?
    ): String {
        val who = userName?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotEmpty() }
        val head = if (who != null) "Subo: ${plain(who)} llego" else "Subo: ya llegue"
        val map = if (latitude != null && longitude != null) {
            String.format(Locale.US, " https://maps.google.com/?q=%.5f,%.5f", latitude, longitude)
        } else {
            ""
        }
        val tail = " a las $time.$map"
        // Si no cabe en un SMS, se acorta el nombre del sitio, no el enlace.
        val room = MAX_LENGTH - head.length - " a ".length - tail.length
        val name = plain(placeName.trim())
        val place = when {
            room <= 5 -> ""
            name.length <= room -> name
            else -> name.take(room - 3).trimEnd() + "..."
        }
        return if (place.isEmpty()) "$head$tail" else "$head a $place$tail"
    }

    /**
     * El texto sin tildes ni signos raros, para que quepa en el alfabeto de
     * los SMS (la ñ sí está en él y se queda).
     */
    fun plain(text: String): String {
        val kept = text.replace("ñ", "\u0001").replace("Ñ", "\u0002")
        return Normalizer.normalize(kept, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("\u0001", "ñ").replace("\u0002", "Ñ")
            .replace(Regex("[«»“”]"), "\"")
            .replace(Regex("[‘’]"), "'")
            .replace("…", "...")
            .replace(Regex("[–—]"), "-")
    }
}
