package com.example.cuicatl

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteOrder

object AudioExtractor {

    class CancelledException : IOException("Extracción cancelada")

    private const val ARTISTA = "Cuicatl"

    // Frecuencias que MP3 admite directamente; otras se convierten a 44100 Hz
    private val SAMPLE_RATES_MP3 = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    /**
     * Extrae el audio de [source] y lo guarda como .mp3. Bloqueante: llamar desde un hilo.
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
            extractor.selectTrack(index)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val fileName = limpiarNombre(name)
            val titulo = fileName.removeSuffix(".mp3")

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                escribirConMediaStore(
                    context, extractor, format, mime, durationUs, fileName, titulo,
                    isCancelled, onProgress
                )
            } else {
                escribirEnCarpetaApp(
                    context, extractor, format, mime, durationUs, fileName, titulo,
                    isCancelled, onProgress
                )
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
        context: Context, extractor: MediaExtractor, format: MediaFormat, mime: String,
        durationUs: Long, fileName: String, titulo: String,
        isCancelled: () -> Boolean, onProgress: (Int) -> Unit
    ): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Cuicatl")
            put(MediaStore.Audio.Media.TITLE, titulo)
            put(MediaStore.Audio.Media.ARTIST, ARTISTA)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("No se pudo crear el archivo")

        try {
            val salida = resolver.openOutputStream(uri)
                ?: throw IOException("No se pudo escribir el archivo")
            salida.use {
                transcodificar(
                    extractor, format, mime, durationUs,
                    BufferedOutputStream(it, 64 * 1024), titulo, isCancelled, onProgress
                )
            }

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
        context: Context, extractor: MediaExtractor, format: MediaFormat, mime: String,
        durationUs: Long, fileName: String, titulo: String,
        isCancelled: () -> Boolean, onProgress: (Int) -> Unit
    ): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        dir.mkdirs()
        var file = File(dir, fileName)
        var i = 1
        while (file.exists()) {
            file = File(dir, fileName.removeSuffix(".mp3") + " ($i).mp3")
            i++
        }
        try {
            FileOutputStream(file).use { fos ->
                transcodificar(
                    extractor, format, mime, durationUs,
                    BufferedOutputStream(fos, 64 * 1024), titulo, isCancelled, onProgress
                )
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf("audio/mpeg"), null
        )
        return file.absolutePath
    }

    /**
     * Audio del video -> PCM (MediaCodec) -> MP3 (LAME) -> [out].
     */
    private fun transcodificar(
        extractor: MediaExtractor, format: MediaFormat, mime: String, durationUs: Long,
        out: OutputStream, titulo: String,
        isCancelled: () -> Boolean, onProgress: (Int) -> Unit
    ) {
        val decoder = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw IOException("Tu teléfono no puede decodificar el audio de este video («$mime»).")
        }

        var lame: AndroidLame? = null
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var entradaLista = false
            var salidaLista = false
            var canales = 2
            var mp3buf = ByteArray(0)
            var ultimo = -1

            while (!salidaLista) {
                if (isCancelled()) throw CancelledException()

                // 1) Alimentar el decodificador con audio comprimido
                if (!entradaLista) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)
                            ?: throw IOException("No se pudo leer el audio del video")
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            entradaLista = true
                        } else {
                            val t = extractor.sampleTime
                            decoder.queueInputBuffer(i, 0, size, t, 0)
                            if (durationUs > 0) {
                                val pct = (t * 100 / durationUs).toInt().coerceIn(0, 99)
                                if (pct != ultimo) {
                                    ultimo = pct
                                    onProgress(pct)
                                }
                            }
                            extractor.advance()
                        }
                    }
                }

                // 2) Tomar el PCM decodificado y pasarlo a MP3
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0) {
                        var enc = lame
                        if (enc == null) {
                            // El formato real de salida se conoce al recibir el primer bloque
                            val of = decoder.outputFormat
                            val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            canales = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
                            ) {
                                throw IOException("El audio de este video usa un formato PCM no compatible.")
                            }
                            enc = crearLame(rate, if (canales == 1) 1 else 2, titulo)
                            lame = enc
                        }

                        val bb = decoder.getOutputBuffer(o)
                        if (bb != null) {
                            bb.position(info.offset)
                            bb.limit(info.offset + info.size)
                            val pcm = ShortArray(info.size / 2)
                            bb.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm)

                            val porCanal = pcm.size / canales
                            if (porCanal > 0) {
                                val necesario = porCanal * 5 / 4 + 7200
                                if (mp3buf.size < necesario) mp3buf = ByteArray(necesario)

                                val bytes = when {
                                    canales == 1 -> enc.encode(pcm, pcm, porCanal, mp3buf)
                                    canales == 2 -> enc.encodeBufferInterLeaved(pcm, porCanal, mp3buf)
                                    else -> {
                                        // Más de 2 canales: se usan solo los frontales (izq. y der.)
                                        val estereo = ShortArray(porCanal * 2)
                                        for (n in 0 until porCanal) {
                                            estereo[2 * n] = pcm[n * canales]
                                            estereo[2 * n + 1] = pcm[n * canales + 1]
                                        }
                                        enc.encodeBufferInterLeaved(estereo, porCanal, mp3buf)
                                    }
                                }
                                if (bytes < 0) throw IOException("Error al codificar el MP3 (código $bytes)")
                                if (bytes > 0) out.write(mp3buf, 0, bytes)
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) salidaLista = true
                }
            }

            // 3) Vaciar lo que LAME tenga pendiente
            val enc = lame ?: throw IOException("No se pudo obtener audio de este video")
            if (mp3buf.size < 7200) mp3buf = ByteArray(7200)
            val resto = enc.flush(mp3buf)
            if (resto > 0) out.write(mp3buf, 0, resto)
            out.flush()
            onProgress(100)
        } finally {
            try {
                lame?.close()
            } catch (e: Exception) {
                // nada que hacer
            }
            try {
                decoder.stop()
            } catch (e: Exception) {
                // ya estaba detenido
            }
            decoder.release()
        }
    }

    private fun crearLame(sampleRate: Int, canalesSalida: Int, titulo: String): AndroidLame {
        val salida = if (sampleRate in SAMPLE_RATES_MP3) sampleRate else 44100
        return LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutSampleRate(salida)
            .setOutChannels(canalesSalida)
            .setMode(if (canalesSalida == 1) LameBuilder.Mode.MONO else LameBuilder.Mode.JSTEREO)
            .setOutBitrate(192)
            .setQuality(3)
            .setId3tagTitle(titulo)
            .setId3tagArtist(ARTISTA)
            .build()
    }

    private fun limpiarNombre(raw: String): String {
        var n = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.')
        if (n.isBlank()) n = "audio_${System.currentTimeMillis()}"
        val ext = n.substringAfterLast('.', "")
        if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) n = n.substringBeforeLast('.')
        return "$n.mp3"
    }
}