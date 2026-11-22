package com.example.cuicatl

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/** Tema de la app: seguir al sistema, siempre claro o siempre oscuro. Se guarda en preferencias. */
object ThemeHelper {

    private const val PREFS = "settings_prefs"
    private const val KEY = "tema"

    private val modos = intArrayOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES
    )

    private fun guardado(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(0, 2)

    /** Se llama al iniciar la pantalla principal y el reproductor, antes de crear la vista. */
    fun aplicar(context: Context) {
        AppCompatDelegate.setDefaultNightMode(modos[guardado(context)])
    }

    fun mostrarDialogo(activity: AppCompatActivity) {
        val opciones = arrayOf("Seguir al sistema", "Claro", "Oscuro")
        AlertDialog.Builder(activity)
            .setTitle("Tema")
            .setSingleChoiceItems(opciones, guardado(activity)) { dialog, which ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY, which) }
                dialog.dismiss()
                // Cambiar el modo recrea las pantallas abiertas con los colores nuevos
                AppCompatDelegate.setDefaultNightMode(modos[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
