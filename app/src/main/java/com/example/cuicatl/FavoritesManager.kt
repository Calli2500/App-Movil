package com.example.cuicatl

import android.content.Context
import androidx.core.content.edit

/** Guarda las rutas de las canciones favoritas. Es la única fuente de favoritos de la app. */
object FavoritesManager {

    private const val PREFS = "library_prefs"
    private const val KEY_FAVORITES = "favorite_songs"

    fun obtenerFavoritos(context: Context): MutableSet<String> {
        val preferencias = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        return preferencias.getStringSet(KEY_FAVORITES, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
    }

    fun esFavorita(context: Context, song: Song): Boolean {
        return obtenerFavoritos(context).contains(song.path)
    }

    fun alternarFavorito(context: Context, song: Song): Boolean {
        val favoritos = obtenerFavoritos(context)

        val ahoraEsFavorita = if (favoritos.contains(song.path)) {
            favoritos.remove(song.path)
            false
        } else {
            favoritos.add(song.path)
            true
        }

        guardar(context, favoritos)
        return ahoraEsFavorita
    }

    /** Se llama al eliminar una canción: la saca de favoritos si estaba. */
    fun quitar(context: Context, song: Song) {
        val favoritos = obtenerFavoritos(context)
        if (favoritos.remove(song.path)) guardar(context, favoritos)
    }

    private fun guardar(context: Context, favoritos: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_FAVORITES, favoritos)
        }
    }
}