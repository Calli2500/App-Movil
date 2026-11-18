package com.example.cuicatl

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Una playlist: nombre y rutas de sus canciones (en el orden en que se agregaron). */
data class Playlist(val id: String, val name: String, val paths: List<String>)

/** Guarda las playlists en SharedPreferences como JSON. */
object PlaylistStore {

    private const val PREFS = "playlists"
    private const val KEY = "data"
    private const val TAG = "CuicatlPlaylists"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<Playlist> {
        val json = prefs(c).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val rutas = o.getJSONArray("paths")
                Playlist(
                    o.getString("id"),
                    o.getString("name"),
                    List(rutas.length()) { j -> rutas.getString(j) }
                )
            }
        } catch (e: JSONException) {
            Log.w(TAG, "No se pudieron leer las playlists", e)
            emptyList()
        }
    }

    private fun guardar(c: Context, listas: List<Playlist>) {
        val arr = JSONArray()
        listas.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("paths", JSONArray(p.paths))
            )
        }
        prefs(c).edit { putString(KEY, arr.toString()) }
    }

    fun get(c: Context, id: String): Playlist? = all(c).firstOrNull { it.id == id }

    fun create(c: Context, name: String): Playlist {
        val nueva = Playlist(UUID.randomUUID().toString(), name.trim(), emptyList())
        guardar(c, all(c) + nueva)
        return nueva
    }

    fun rename(c: Context, id: String, name: String) {
        guardar(c, all(c).map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun delete(c: Context, id: String) {
        guardar(c, all(c).filter { it.id != id })
    }

    /** Devuelve false si la canción ya estaba en la playlist. */
    fun addSong(c: Context, id: String, path: String): Boolean {
        val listas = all(c)
        val p = listas.firstOrNull { it.id == id } ?: return false
        if (path in p.paths) return false
        guardar(c, listas.map { if (it.id == id) it.copy(paths = it.paths + path) else it })
        return true
    }

    fun removeSong(c: Context, id: String, path: String) {
        guardar(c, all(c).map { if (it.id == id) it.copy(paths = it.paths - path) else it })
    }

    /** Se llama al eliminar una canción del teléfono: la saca de todas las playlists. */
    fun forgetPath(c: Context, path: String) {
        val listas = all(c)
        if (listas.none { path in it.paths }) return
        guardar(c, listas.map { it.copy(paths = it.paths - path) })
    }
}

object PlaylistDialogs {

    /** Pide un nombre (para crear o renombrar) y llama a onOk solo si no está vacío. */
    fun pedirNombre(activity: Activity, titulo: String, inicial: String, onOk: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(inicial)
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val contenedor = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setView(contenedor)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = input.text.toString().trim()
                if (nombre.isNotEmpty()) onOk(nombre)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}