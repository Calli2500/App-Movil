package com.example.cuicatl

import android.content.Context

object FavoritesManager {

    private const val PREFS = "library_prefs"
    private const val KEY_FAVORITES = "favorite_songs"

    fun obtenerFavoritos(context: Context): MutableSet<String> {
        val preferencias = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

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

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAVORITES, favoritos)
            .apply()

        return ahoraEsFavorita
    }
}