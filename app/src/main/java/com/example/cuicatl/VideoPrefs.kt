package com.example.cuicatl

import android.content.Context
import androidx.core.content.edit

/** Preferencias de videos: nombres personalizados y videos ocultos (solo dentro de la app). */
object VideoPrefs {
    private const val ARCHIVO = "video_prefs"
    private const val OCULTOS = "ocultos"
    private const val PREFIJO_NOMBRE = "nombre_"

    private fun prefs(c: Context) = c.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    fun ocultos(c: Context): Set<Long> =
        prefs(c).getStringSet(OCULTOS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun ocultar(c: Context, id: Long) = cambiarOcultos(c) { it.add(id.toString()) }

    fun mostrar(c: Context, id: Long) = cambiarOcultos(c) { it.remove(id.toString()) }

    // Se copia el conjunto: el que devuelve SharedPreferences no se debe modificar directamente
    private fun cambiarOcultos(c: Context, cambio: (MutableSet<String>) -> Unit) {
        val copia = prefs(c).getStringSet(OCULTOS, emptySet()).orEmpty().toMutableSet()
        cambio(copia)
        prefs(c).edit { putStringSet(OCULTOS, copia) }
    }

    fun nombre(c: Context, id: Long): String? = prefs(c).getString(PREFIJO_NOMBRE + id, null)

    fun renombrar(c: Context, id: Long, nuevo: String) =
        prefs(c).edit { putString(PREFIJO_NOMBRE + id, nuevo) }

    fun restaurarNombre(c: Context, id: Long) =
        prefs(c).edit { remove(PREFIJO_NOMBRE + id) }
}