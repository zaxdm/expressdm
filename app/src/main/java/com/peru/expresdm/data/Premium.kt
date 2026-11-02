package com.peru.expresdm.data

import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.data.net.Session

/**
 * Subo Premium: lo que la cuenta de pago tiene de más.
 *
 * Quién es Premium lo dice el servidor, que es donde se activa al confirmar el
 * pago; aquí solo se lee para abrir o cerrar funciones. Sin sesión, o con la
 * fecha vencida, la cuenta es gratuita.
 */
object Premium {

    /** Rutas con mapa sin conexión que guarda una cuenta gratuita. */
    const val FREE_SAVED_ROUTES = 1

    /** Las que guarda una cuenta Premium. */
    const val PREMIUM_SAVED_ROUTES = SavedRoutes.MAX_SAVED

    /** Lugares favoritos de una cuenta gratuita; con Premium no hay tope. */
    const val FREE_FAVORITE_PLACES = 3

    fun isActive(session: Session?, now: Long = System.currentTimeMillis()): Boolean =
        (session?.premiumUntil ?: 0L) > now

    /** Con cuántos días de antelación el perfil avisa de que Premium vence. */
    const val EXPIRY_NOTICE_DAYS = 3

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Premium sigue activo pero vence en [EXPIRY_NOTICE_DAYS] días o menos: toca renovarlo. */
    fun expiresSoon(session: Session?, now: Long = System.currentTimeMillis()): Boolean {
        val until = session?.premiumUntil ?: return false
        return until > now && until - now <= EXPIRY_NOTICE_DAYS * DAY_MS
    }

    /**
     * Solo para la compilación de pruebas: trata la cuenta como Premium, para
     * probar sus funciones sin activarla en el servidor. Lo que el servidor
     * decide (el tope del asistente, el viaje en vivo) no cambia con esto.
     */
    var debugForced = false

    /** La cuenta con la sesión abierta es Premium ahora mismo. */
    val active: Boolean get() = debugForced || isActive(AccountRepository.session.value)

    /** Cuántas rutas puede guardar sin conexión esa cuenta. */
    fun savedRoutesLimit(premium: Boolean = active): Int =
        if (premium) PREMIUM_SAVED_ROUTES else FREE_SAVED_ROUTES

    /** Cuántos lugares favoritos puede tener esa cuenta. */
    fun favoritePlacesLimit(premium: Boolean = active): Int =
        if (premium) Int.MAX_VALUE else FREE_FAVORITE_PLACES
}
