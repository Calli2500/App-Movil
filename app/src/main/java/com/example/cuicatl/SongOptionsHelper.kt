package com.example.cuicatl

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu

class SongOptionsHelper(private val activity: AppCompatActivity) {

    private var pendingSong: Song? = null
    private var pendingDone: (() -> Unit)? = null

    // Debe registrarse al crear la Activity (por eso se construye en onCreate)
    private val picker = activity.registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val song = pendingSong
        if (uri != null && song != null) {
            val ok = SongStore.saveImage(activity, song, uri)
            Toast.makeText(
                activity,
                if (ok) "Imagen guardada" else "No se pudo guardar la imagen",
                Toast.LENGTH_SHORT
            ).show()
            if (ok) pendingDone?.invoke()
        }
    }

    /** Menú de los 3 puntos de cada canción en la lista. */
    fun showSongMenu(anchor: View, song: Song, onChanged: () -> Unit) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, 1, 0, "Cambiar nombre")
            menu.add(0, 2, 1, "Cambiar imagen")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> rename(song, onChanged)
                    2 -> pickImage(song, onChanged)
                }
                true
            }
            show()
        }
    }

    /** Menú de los 3 puntos del reproductor. */
    fun showPlayerMenu(anchor: View, service: MusicService, onChanged: () -> Unit) {
        val song = service.currentSong ?: return
        PopupMenu(activity, anchor).apply {
            menu.add(0, 1, 0, "Cambiar nombre")
            menu.add(0, 2, 1, "Cambiar imagen")
            menu.add(
                0, 3, 2,
                if (service.sleepEndTime > 0) "Temporizador (activo)" else "Apagar reproductor en…"
            )
            menu.add(0, 4, 3, "Repetir canción en bucle").apply {
                isCheckable = true
                isChecked = service.loopEnabled
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> rename(song, onChanged)
                    2 -> pickImage(song, onChanged)
                    3 -> showSleepDialog(service)
                    4 -> {
                        service.setLoop(!service.loopEnabled)
                        onChanged()
                    }
                }
                true
            }
            show()
        }
    }

    fun rename(song: Song, onDone: () -> Unit) {
        val input = EditText(activity).apply {
            setText(SongStore.displayTitle(activity, song))
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val container = FrameLayout(activity).apply {
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
            .setTitle("Cambiar nombre")
            .setMessage("Déjalo vacío para volver al nombre original")
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                SongStore.setCustomTitle(activity, song, input.text.toString())
                onDone()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun pickImage(song: Song, onDone: () -> Unit) {
        pendingSong = song
        pendingDone = onDone
        picker.launch("image/*")
    }

    fun showSleepDialog(service: MusicService) {
        val labels = arrayOf(
            "1 minuto (prueba)", "15 minutos", "30 minutos",
            "45 minutos", "1 hora", "Cancelar temporizador"
        )
        val minutes = intArrayOf(1, 15, 30, 45, 60)
        AlertDialog.Builder(activity)
            .setTitle("Apagar reproductor en…")
            .setItems(labels) { _, which ->
                if (which < minutes.size) {
                    service.setSleepTimer(minutes[which])
                    Toast.makeText(activity, "Se apagará en ${labels[which]}", Toast.LENGTH_SHORT).show()
                } else {
                    service.cancelSleepTimer()
                    Toast.makeText(activity, "Temporizador cancelado", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}