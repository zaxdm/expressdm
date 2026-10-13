package com.peru.expresdm.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.core.view.doOnAttach
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Fotos opcionales del catálogo, con caché y tamaño limitado. */
object RouteImages {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun show(view: ImageView, url: String?, description: String) {
        view.tag = url
        view.contentDescription = description
        view.setImageDrawable(null)
        view.isVisible = false
        if (url.isNullOrBlank() || !url.startsWith("https://")) return
        if (!view.isAttachedToWindow) {
            view.doOnAttach { if (view.tag == url) show(view, url, description) }
            return
        }
        cache.get(url)?.let {
            view.setImageBitmap(it)
            view.isVisible = true
            return
        }
        val owner = view.findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { download(url) } ?: return@launch
            cache.put(url, bitmap)
            // Una tarjeta reciclada puede estar mostrando otro micro.
            if (view.tag == url) {
                view.setImageBitmap(bitmap)
                view.isVisible = true
            }
        }
    }

    /** La foto de [url], de la memoria o de la red. `null` si no hay o no se pudo traer. */
    suspend fun load(url: String?): Bitmap? {
        if (url.isNullOrBlank() || !url.startsWith("https://")) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) { download(url) }?.also { cache.put(url, it) }
    }

    private fun download(url: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { it.readBytesLimited() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (bounds.outWidth / options.inSampleSize > 1000 || bounds.outHeight / options.inSampleSize > 1000) {
                options.inSampleSize *= 2
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Exception) { null }
        finally { connection?.disconnect() }
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray? {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (result.size() + count > 5 * 1024 * 1024) return null
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }
}
