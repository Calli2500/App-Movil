package com.example.cuicatl

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * Datos compartidos entre MainActivity y los fragments de las pestañas.
 * MainActivity lee el teléfono, filtra y ordena; cada fragment solo dibuja su lista.
 */
class LibraryViewModel : ViewModel() {
    val mp3 = MutableLiveData<List<Song>>(emptyList())
    val otros = MutableLiveData<List<Song>>(emptyList())

    /** Texto de búsqueda actual (sirve para decir "Sin resultados" o "Sin archivos"). */
    var consulta: String = ""
}