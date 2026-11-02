package com.peru.expresdm

import com.peru.expresdm.data.PremiumCheckout
import com.peru.expresdm.data.PremiumCheckout.Navigation
import com.peru.expresdm.data.PremiumCheckout.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PremiumCheckoutTest {

    @Test
    fun `la sesion va tras la almohadilla y codificada`() {
        assertEquals(
            "https://subo.example/pagar?app=1&dias=30#t=abc%2Bd%2Fe%3D%20f",
            PremiumCheckout.payUrl("https://subo.example/", 30, "abc+d/e= f")
        )
    }

    @Test
    fun `lee como termino el pago`() {
        assertEquals(Outcome.APPROVED, PremiumCheckout.outcome("subo-app://pago?estado=aprobado"))
        assertEquals(Outcome.PENDING, PremiumCheckout.outcome("subo-app://pago?estado=pendiente"))
        assertEquals(Outcome.REJECTED, PremiumCheckout.outcome("subo-app://pago?x=1&estado=rechazado"))
        assertEquals(Outcome.CANCELLED, PremiumCheckout.outcome("subo-app://pago?estado=cancelado"))
        // Un estado raro o sin estado: no se dice nada.
        assertEquals(Outcome.CANCELLED, PremiumCheckout.outcome("subo-app://pago"))
        assertEquals(Outcome.CANCELLED, PremiumCheckout.outcome("subo-app://pago?estado=otro"))
        // No es el aviso de pago.
        assertNull(PremiumCheckout.outcome("subo-app://otra?estado=aprobado"))
        assertNull(PremiumCheckout.outcome("https://subo.example/pago?estado=aprobado"))
    }

    @Test
    fun `solo https se queda dentro y intent no se abre`() {
        assertEquals(Navigation.STAY, PremiumCheckout.navigation("https://www.mercadopago.com.pe/checkout"))
        assertEquals(Navigation.FINISH, PremiumCheckout.navigation("subo-app://pago?estado=aprobado"))
        assertEquals(Navigation.BLOCK, PremiumCheckout.navigation("intent://scan/#Intent;scheme=x;end"))
        assertEquals(Navigation.BLOCK, PremiumCheckout.navigation("file:///sdcard/a.html"))
        assertEquals(Navigation.BLOCK, PremiumCheckout.navigation("javascript:alert(1)"))
        assertEquals(Navigation.EXTERNAL, PremiumCheckout.navigation("mailto:ayuda@subo.pe"))
        assertEquals(Navigation.EXTERNAL, PremiumCheckout.navigation("http://inseguro.example"))
    }
}
