package com.peru.expresdm

import com.peru.expresdm.data.net.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiUrlTest {

    private val base = "https://ejemplo.supabase.co/functions/v1/server"

    @Test
    fun `toda llamada pide correr en la region de la base de datos`() {
        assertEquals("$base/api/routes?forceFunctionRegion=ca-central-1", ApiClient.urlFor(base, "/api/routes"))
    }

    @Test
    fun `si la ruta ya trae parametros se suma con ampersand`() {
        assertEquals(
            "$base/api/alerts?lat=1&lng=2&forceFunctionRegion=ca-central-1",
            ApiClient.urlFor(base, "/api/alerts?lat=1&lng=2")
        )
    }

    @Test
    fun `no se repite si ya esta`() {
        val url = "/api/me?forceFunctionRegion=ca-central-1"
        assertEquals(base + url, ApiClient.urlFor(base, url))
    }
}
