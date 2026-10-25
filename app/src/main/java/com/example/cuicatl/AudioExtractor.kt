package com.example.cuicatl

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

object AudioExtractor {

    class CancelledException : IOException("Extracción cancelada")

    /**
     * Extrae el audio de [source] y lo guarda como .m4a. Bloqueante: llamar desde un hilo.
     * Devuelve la ruta final del archivo.
     */
    fun extract(
        context: Context,
        source: Uri,
        name: String,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, null)

            var index = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    index = i
                    format = f
                    break
                }
            }
            if (index < 0 || format == null) throw IOException("Este video no tiene pista de audio")

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime != "audio/mp4a-latm") {
                throw IOException(
                    "El audio de este video está en formato «$mime». Por ahora solo se pueden " +
                            "extraer pistas AAC, que son las de casi todos los videos de celular."
                )
            }

            extractor.selectTrack(index)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val fileName = limpiarNombre(name)

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                escribirConMediaStore(context, extractor, format, durationUs, fileName, isCancelled, onProgress)
            } else {
                escribirEnCarpetaApp(context, extractor, format, durationUs, fileName, isCancelled, onProgress)
            }
        } finally {
            extractor.release()
        }
    }

    /** Un cuadro del video para usarlo como portada del audio. */
    fun videoFrame(context: Context, uri: Uri): Bitmap? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0)
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        null
    }

    // ---------- Android 10+: Música/Cuicatl ----------
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun escribirConMediaStore(
        context: Context, extractor: MediaExtractor, format: MediaFormat, durationUs: Long,
        fileName: String, isCancelled: () -> Boolean, onProgress: (Int) -> Unit
    ): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Cuicatl")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("No se pudo crear el archivo")

        try {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    volcar(extractor, muxer, format, durationUs, isCancelled, onProgress)
                } finally {
                    muxer.release()
                }
            } ?: throw IOException("No se pudo escribir el archivo")

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
    private fun escribirEnCarpetaApp(
        context: Context, extractor: MediaExtractor, format: MediaFormat, durationUs: Long,
        fileName: String, isCancelled: () -> Boolean, onProgress: (Int) -> Unit
    ): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        dir.mkdirs()
        var file = File(dir, fileName)
        var i = 1
        while (file.exists()) {
            file = File(dir, fileName.removeSuffix(".m4a") + " ($i).m4a")
            i++
        }
        try {
            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                volcar(extractor, muxer, format, durationUs, isCancelled, onProgress)
            } finally {
                muxer.release()
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("audio/mp4"), null)
        return file.absolutePath
    }

    /** Copia las muestras de audio del extractor al archivo de salida. */
    private fun volcar(
        extractor: MediaExtractor, muxer: MediaMuxer, format: MediaFormat, durationUs: Long,
        isCancelled: () -> Boolean, onProgress: (Int) -> Unit
    ) {
        val track = muxer.addTrack(format)
        muxer.start()
        try {
            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var ultimo = -1
            while (true) {
                if (isCancelled()) throw CancelledException()
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(track, buffer, info)

                if (durationUs > 0) {
                    val pct = (extractor.sampleTime * 100 / durationUs).toInt().coerceIn(0, 100)
                    if (pct != ultimo) {
                        ultimo = pct
                        onProgress(pct)
                    }
                }
                extractor.advance()
            }
            onProgress(100)
        } finally {
            try {
                muxer.stop()
            } catch (e: Exception) {
                // si falló antes de escribir algo, stop() también falla; se ignora
            }
        }
    }

    private fun limpiarNombre(raw: String): String {
        var n = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.')
        if (n.isBlank()) n = "audio_${System.currentTimeMillis()}"
        val ext = n.substringAfterLast('.', "")
        if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) n = n.substringBeforeLast('.')
        return "$n.m4a"
    }
}