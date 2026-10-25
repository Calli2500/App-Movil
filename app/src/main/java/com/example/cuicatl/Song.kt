package com.example.cuicatl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

data class Song(
    val path: String,
    val originalTitle: String,
    val artist: String
)

object SongStore {

    private fun prefs(c: Context) =
        c.getSharedPreferences("song_store", Context.MODE_PRIVATE)

    fun displayTitle(c: Context, song: Song): String =
        prefs(c).getString("name_${song.path}", null)?.takeIf { it.isNotBlank() }
            ?: song.originalTitle

    fun setCustomTitle(c: Context, song: Song, name: String) {
        val editor = prefs(c).edit()
        if (name.isBlank()) editor.remove("name_${song.path}")
        else editor.putString("name_${song.path}", name.trim())
        editor.apply()
    }

    fun saveImage(c: Context, song: Song, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = c.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false

            val dir = File(c.filesDir, "covers").apply { mkdirs() }
            val file = File(dir, "${song.path.hashCode()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

            prefs(c).edit().putString("img_${song.path}", file.absolutePath).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun saveBitmap(c: Context, song: Song, bitmap: Bitmap) {
        val dir = File(c.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "${song.path.hashCode()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        prefs(c).edit().putString("img_${song.path}", file.absolutePath).apply()
    }

    /** Imagen personalizada; si no hay, la carátula incrustada en el MP3; si no, null. */
    fun loadCover(c: Context, song: Song): Bitmap? {
        prefs(c).getString("img_${song.path}", null)?.let { p ->
            if (File(p).exists()) BitmapFactory.decodeFile(p)?.let { return it }
        }
        return try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(song.path)
                r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } finally {
                r.release()
            }
        } catch (e: Exception) {
            null
        }
    }
}