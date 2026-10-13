package com.peru.expresdm

import com.peru.expresdm.data.AppUpdates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lo que la app entiende del archivo de la web que dice cuál es la última versión. */
@RunWith(RobolectricTestRunner::class)
class AppUpdatesTest {

    private val carpeta = "https://subo.example/descargas"
    private val archivo = """{"versionCode": 3, "versionName": "1.2", "notes": "Novedades", "notes_en": "News", "url": "subo.apk"}"""

    @Test
    fun `ofrece la version de la web si es posterior a la instalada`() {
        val nueva = AppUpdates.parse(archivo, installed = 2, folder = carpeta, language = "es")!!

        assertEquals(3, nueva.versionCode)
        assertEquals("1.2", nueva.versionName)
        assertEquals("Novedades", nueva.notes)
        assertEquals("$carpeta/subo.apk", nueva.url)
    }

    @Test
    fun `con la ultima version puesta no ofrece nada`() {
        assertNull(AppUpdates.parse(archivo, installed = 3, folder = carpeta, language = "es"))
        assertNull(AppUpdates.parse(archivo, installed = 4, folder = carpeta, language = "es"))
    }

    @Test
    fun `cuenta las novedades en el idioma del telefono y acepta una direccion completa`() {
        val nueva = AppUpdates.parse(
            """{"versionCode": 3, "notes": "Novedades", "notes_en": "News", "url": "https://otro.example/subo.apk"}""",
            installed = 1, folder = carpeta, language = "en"
        )!!

        assertEquals("News", nueva.notes)
        assertEquals("https://otro.example/subo.apk", nueva.url)
        // Sin nombre de versión se enseña su número.
        assertEquals("3", nueva.versionName)
    }

    @Test
    fun `un archivo ilegible no ofrece nada`() {
        assertNull(AppUpdates.parse("<html>No encontrado</html>", installed = 1, folder = carpeta, language = "es"))
        assertNull(AppUpdates.parse("""{"versionName": "1.2"}""", installed = 1, folder = carpeta, language = "es"))
    }
}
