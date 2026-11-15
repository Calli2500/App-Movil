package com.example.cuicatl

import android.view.View

/** Lo que los fragments de la biblioteca le piden a la activity que los contiene. */
interface LibraryHost {
    fun onSongClick(songs: List<Song>, index: Int)
    fun onSongOptions(anchor: View, song: Song)

    /** Alterna el favorito y devuelve el estado nuevo (true = quedó como favorita). */
    fun onFavoriteClick(song: Song): Boolean
}