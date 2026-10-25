package com.example.cuicatl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

data class JamendoTrack(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val imageUrl: String,
    val downloadUrl: String,
    val downloadAllowed: Boolean,
    val licenseUrl: String
)

object Jamendo {

    // 👇 PEGA AQUÍ tu client_id de devportal.jamendo.com
    const val CLIENT_ID = "cac46300"

    private const val BASE = "https://api.jamendo.com/v3.0"

    /** URL oficial de descarga: Jamendo responde con una redirección al archivo MP3. */
    fun fileUrl(trackId: String): String =
        "$BASE/tracks/file/?client_id=$CLIENT_ID&id=$trackId&audioformat=mp32"

    /** Bloqueante: llamar desde un hilo. Con [query] vacío devuelve lo más popular de la semana. */
    fun search(query: String, offset: Int, limit: Int = 20): List<JamendoTrack> {
        if (CLIENT_ID.isBlank() || CLIENT_ID.startsWith("TU_")) {
            throw IOException("Falta tu client_id de Jamendo (archivo Jamendo.kt)")
        }
        val filtro = if (query.isBlank()) {
            "&order=popularity_week"
        } else {
            "&search=" + URLEncoder.encode(query, "UTF-8")
        }
        val url = "$BASE/tracks/?client_id=$CLIENT_ID&format=json" +
                "&limit=$limit&offset=$offset&audiodlformat=mp32&imagesize=300$filtro"

        val json = JSONObject(httpGet(url))
        val headers = json.getJSONObject("headers")
        if (headers.optString("status") != "success") {
            throw IOException(headers.optString("error_message").ifBlank { "Error de Jamendo" })
        }
        val arr = json.getJSONArray("results")
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            JamendoTrack(
                id = o.optString("id"),
                name = o.optString("name"),
                artist = o.optString("artist_name"),
                album = o.optString("album_name"),
                durationSec = o.optInt("duration"),
                imageUrl = o.optString("image"),
                downloadUrl = o.optString("audiodownload"),
                downloadAllowed = o.optBoolean("audiodownload_allowed", false),
                licenseUrl = o.optString("license_ccurl")
            )
        }
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Jamendo respondió $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Descarga imágenes en segundo plano y las guarda en memoria. */
object ImageLoader {

    private val cache = object : LruCache<String, Bitmap>(8 * 1024) { // 8 MB (en KB)
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())

    fun load(url: String, target: ImageView) {
        target.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.setImageDrawable(null)
        if (url.isBlank()) return
        pool.execute {
            val bmp = loadBlocking(url)
            if (bmp != null) {
                ui.post { if (target.tag == url) target.setImageBitmap(bmp) }
            }
        }
    }

    /** Bloqueante: llamar desde un hilo. */
    fun loadBlocking(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            try {
                BitmapFactory.decodeStream(c.inputStream)?.also { cache.put(url, it) }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}