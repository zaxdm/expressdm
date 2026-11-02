package com.peru.expresdm.data

import java.net.URI
import java.net.URLEncoder

/**
 * El pago con Mercado Pago sin salir de la app: la página `/pagar` de la web
 * se abre dentro de una ventana de la app y, al terminar, avisa con una
 * dirección `subo-app://pago?estado=…`.
 */
object PremiumCheckout {

    /** Cómo terminó el pago, según lo que dice la web al volver. */
    enum class Outcome { APPROVED, PENDING, REJECTED, CANCELLED }

    /** Qué hacer con una dirección a la que quiere ir la página. */
    enum class Navigation {
        /** Se queda dentro (Mercado Pago, la verificación del banco…). */
        STAY,

        /** Es el aviso de que terminó: se cierra la ventana. */
        FINISH,

        /** Otra app (correo, teléfono…): se abre fuera. */
        EXTERNAL,

        /** No se abre: `intent:`, archivos y demás. */
        BLOCK
    }

    private const val SCHEME = "subo-app"
    private const val HOST = "pago"

    /** Esquemas que nunca se abren, ni dentro ni fuera. */
    private val blocked = setOf("intent", "file", "content", "javascript", "data", "about", "blob")

    /**
     * La página de pago de [days] días. La sesión va tras `#`: esa parte de la
     * dirección no sale del teléfono, así que no llega a ningún servidor.
     */
    fun payUrl(webUrl: String, days: Int, token: String): String {
        val encoded = URLEncoder.encode(token, "UTF-8").replace("+", "%20")
        return "${webUrl.trimEnd('/')}/pagar?app=1&dias=$days#t=$encoded"
    }

    fun navigation(url: String): Navigation {
        val scheme = url.substringBefore(':', "").lowercase()
        return when {
            scheme == "https" -> Navigation.STAY
            scheme == SCHEME -> Navigation.FINISH
            scheme.isEmpty() || scheme in blocked -> Navigation.BLOCK
            else -> Navigation.EXTERNAL
        }
    }

    /**
     * Lee `subo-app://pago?estado=aprobado`. `null` si no es ese aviso; un
     * estado que no se conoce cuenta como cancelado (no se dice nada).
     */
    fun outcome(url: String): Outcome? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) return null
        val state = uri.rawQuery.orEmpty().split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == "estado" }
            ?.getOrNull(1)
            ?.lowercase()
        return when (state) {
            "aprobado" -> Outcome.APPROVED
            "pendiente" -> Outcome.PENDING
            "rechazado" -> Outcome.REJECTED
            else -> Outcome.CANCELLED
        }
    }
}
