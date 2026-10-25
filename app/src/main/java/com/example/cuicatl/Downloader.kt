package com.example.cuicatl

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object Downloader {

    class CancelledException : IOException("Descarga cancelada")

    private val EXTENSIONES = setOf("mp3", "m4a", "ogg", "wav", "flac", "aac", "opus")

    /**
     * Descarga [url] a la biblioteca. Bloqueante: llamar desde un hilo.
     * Devuelve la ruta final del archivo.
     */
    fun download(
        context: Context,
        url: String,
        fileName: String? = null,
        isCancelled: () -> Boolean = { false },
        onStart: (String) -> Unit = {},
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): String {
        val conn = abrirConexion(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("El servidor respondió $code")

            val mime = conn.contentType ?: ""

            // Se leen los primeros bytes para reconocer el formato real,
            // porque algunos servidores mandan el tipo equivocado (ej. MP3 como text/html).
            val input = BufferedInputStream(conn.inputStream, 64 * 1024)
            input.mark(1024)
            val header = ByteArray(512)
            var n = 0
            while (n < header.size) {
                val r = input.read(header, n, header.size - n)
                if (r < 0) break
                n += r
            }
            input.reset()

            val tipoReal = detectarAudio(header, n)
            if (tipoReal == null && mime.startsWith("text/", ignoreCase = true)) {
                throw IOException(
                    "Ese enlace es una página web, no un archivo de audio directo (${conn.url.host})."
                )
            }

            val name = limpiarNombre(fileName ?: nombreSugerido(conn, url), tipoReal ?: extensionPorMime(mime))
            onStart(name)
            val total = conn.contentLengthLong

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                guardarConMediaStore(context, input, name, total, isCancelled, onProgress)
            } else {
                guardarEnCarpetaApp(context, input, name, total, isCancelled, onProgress)
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Android 10+: Música/Cuicatl ----------
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun guardarConMediaStore(
        context: Context, input: InputStream, name: String, total: Long,
        isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit
    ): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimePorNombre(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Cuicatl")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IOException("No se pudo crear el archivo")

        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("No se pudo escribir el archivo")
            out.use { input.use { inp -> copiar(inp, it, total, isCancelled, onProgress) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: ""
    }

    // ---------- Android 9 o menor: carpeta privada de la app ----------
    private fun guardarEnCarpetaApp(
        context: Context, input: InputStream, name: String, total: Long,
        isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit
    ): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        dir.mkdirs()
        val file = archivoUnico(dir, name)
        try {
            file.outputStream().use { out ->
                input.use { inp -> copiar(inp, out, total, isCancelled, onProgress) }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf(mimePorNombre(name)), null
        )
        return file.absolutePath
    }

    // ---------- Utilidades ----------
    private fun copiar(
        input: InputStream, output: OutputStream, total: Long,
        isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit
    ) {
        val buffer = ByteArray(16 * 1024)
        var done = 0L
        var ultimo = 0L
        while (true) {
            if (isCancelled()) throw CancelledException()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            done += n
            val ahora = System.currentTimeMillis()
            if (ahora - ultimo > 200) {
                ultimo = ahora
                onProgress(done, total)
            }
        }
        onProgress(done, total)
    }

    /** Reconoce el formato de audio por sus primeros bytes. Devuelve la extensión o null. */
    private fun detectarAudio(h: ByteArray, n: Int): String? {
        if (n < 4) return null
        fun txt(i: Int, len: Int) = String(h, i, len, Charsets.ISO_8859_1)
        val b0 = h[0].toInt() and 0xFF
        val b1 = h[1].toInt() and 0xFF
        return when {
            txt(0, 3) == "ID3" -> "mp3"
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 ->
                if ((b1 and 0x06) == 0) "aac" else "mp3" // ADTS (AAC) vs. cuadro MPEG
            txt(0, 4) == "OggS" -> "ogg"
            txt(0, 4) == "fLaC" -> "flac"
            n >= 12 && txt(0, 4) == "RIFF" && txt(8, 4) == "WAVE" -> "wav"
            n >= 12 && txt(4, 4) == "ftyp" -> "m4a"
            else -> null
        }
    }

    /** Abre la conexión siguiendo redirecciones (incluso http -> https). */
    private fun abrirConexion(urlInicial: String): HttpURLConnection {
        var actual = urlInicial
        repeat(6) {
            val c = URL(actual).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Cuicatl")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirección inválida")
                actual = URL(URL(actual), loc).toString()
                c.disconnect()
            } else {
                return c
            }
        }
        throw IOException("Demasiadas redirecciones")
    }

    private fun nombreSugerido(conn: HttpURLConnection, url: String): String {
        val deCabecera = conn.getHeaderField("Content-Disposition")?.let { cd ->
            Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(cd)?.groupValues?.get(1)?.let { Uri.decode(it) }
        }?.takeIf { it.isNotBlank() }
        val deUrl = Uri.decode(Uri.parse(url).lastPathSegment ?: "").takeIf { it.isNotBlank() }
        return deCabecera ?: deUrl ?: "descarga_${System.currentTimeMillis()}"
    }

    private fun limpiarNombre(raw: String, extensionPorDefecto: String): String {
        var n = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.')
        if (n.isBlank()) n = "descarga_${System.currentTimeMillis()}"
        val ext = n.substringAfterLast('.', "").lowercase()
        if (ext !in EXTENSIONES) n += ".$extensionPorDefecto"
        return n
    }

    private fun extensionPorMime(mime: String): String {
        val m = mime.lowercase()
        return when {
            "mpeg" in m || "mp3" in m -> "mp3"
            "mp4" in m || "m4a" in m -> "m4a"
            "ogg" in m -> "ogg"
            "wav" in m -> "wav"
            "flac" in m -> "flac"
            "aac" in m -> "aac"
            else -> "mp3"
        }
    }

    private fun mimePorNombre(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "m4a" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/x-wav"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            else -> "audio/mpeg"
        }

    private fun archivoUnico(dir: File, nombre: String): File {
        var f = File(dir, nombre)
        if (!f.exists()) return f
        val base = nombre.substringBeforeLast('.', nombre)
        val ext = if (nombre.contains('.')) "." + nombre.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }
}