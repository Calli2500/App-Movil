package com.example.cuicatl

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.snackbar.Snackbar
import java.io.File
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * onDeleted se llama cuando una canción ya se eliminó (sirve para sacarla de la cola del reproductor).
 */
class SongOptionsHelper(
    private val activity: AppCompatActivity,
    private val onDeleted: (Song) -> Unit = {}
) {

    private var pendingSong: Song? = null
    private var pendingDone: (() -> Unit)? = null

    private var deleteSong: Song? = null
    private var deleteUri: Uri? = null
    private var deleteDone: (() -> Unit)? = null

    // Los launchers deben registrarse al crear la Activity (por eso se construye en onCreate)
    private val picker = activity.registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val song = pendingSong
        if (uri != null && song != null) {
            val ok = SongStore.saveImage(activity, song, uri)
            mensaje(if (ok) "Imagen guardada" else "No se pudo guardar la imagen")
            if (ok) pendingDone?.invoke()
        }
    }

    // Respuesta del sistema cuando pide permiso para borrar un archivo que no es de la app
    private val deleteLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val song = deleteSong ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                // En Android 10 se concede el permiso y hay que repetir el borrado
                val uri = deleteUri
                val filas = try {
                    if (uri != null) activity.contentResolver.delete(uri, null, null) else 0
                } catch (e: Exception) {
                    0
                }
                if (filas > 0) terminarBorrado(song) else borrarArchivo(song)
            } else {
                // En Android 11 o superior el sistema ya lo borró
                terminarBorrado(song)
            }
        } else {
            mensaje("No se eliminó la canción")
            limpiarBorrado()
        }
    }

    /** Menú de los 3 puntos de cada canción en la lista. */
    fun showSongMenu(anchor: View, song: Song, onChanged: () -> Unit) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, 1, 0, "Cambiar nombre")
            menu.add(0, 2, 1, "Cambiar imagen")
            menu.add(0, 3, 2, "Editar canción…")
            menu.add(0, 4, 3, "Eliminar canción")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> rename(song, onChanged)
                    2 -> pickImage(song, onChanged)
                    3 -> activity.startActivity(EditSongActivity.intentFor(activity, song))
                    4 -> confirmarEliminar(song, onChanged)
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
            menu.add(0, 5, 4, "Eliminar canción")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> rename(song, onChanged)
                    2 -> pickImage(song, onChanged)
                    3 -> showSleepDialog(service)
                    4 -> {
                        service.setLoop(!service.loopEnabled)
                        onChanged()
                    }
                    5 -> confirmarEliminar(song, onChanged)
                }
                true
            }
            show()
        }
    }

    // ---------- Cambiar nombre (con aviso al salir sin guardar) ----------
    fun rename(song: Song, onDone: () -> Unit) {
        val actual = SongStore.displayTitle(activity, song)
        val input = EditText(activity).apply {
            setText(actual)
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
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Cambiar nombre")
            .setMessage("Déjalo vacío para volver al nombre original")
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                SongStore.setCustomTitle(activity, song, input.text.toString())
                onDone()
            }
            .setNegativeButton("Cancelar", null)
            .create()

        fun intentarSalir() {
            if (input.text.toString().trim() != actual) {
                AlertDialog.Builder(activity)
                    .setTitle("¿Descartar cambios?")
                    .setMessage("Si sales ahora, se perderá el nombre que escribiste.")
                    .setPositiveButton("Descartar") { _, _ -> dialog.dismiss() }
                    .setNegativeButton("Seguir editando", null)
                    .show()
            } else {
                dialog.dismiss()
            }
        }

        dialog.setCanceledOnTouchOutside(false)
        // Botón atrás: también pasa por la confirmación
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                intentarSalir()
                true
            } else false
        }
        // El botón Cancelar no cierra solo: primero revisa si hay cambios
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { intentarSalir() }
        }
        dialog.show()
    }

    fun pickImage(song: Song, onDone: () -> Unit) {
        pendingSong = song
        pendingDone = onDone
        picker.launch("image/*")
    }

    fun showSleepDialog(service: MusicService) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()

        val tvMinutos = TextView(activity).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            text = textoMinutos(30)
        }
        val slider = SeekBar(activity).apply {
            max = 119          // 0..119  ->  1..120 minutos
            progress = 29      // empieza en 30 minutos
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvMinutos.text = textoMinutos(progress + 1)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val contenido = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(tvMinutos)
            addView(slider)
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle("Apagar reproductor en…")
            .setView(contenido)
            .setPositiveButton("Programar") { _, _ ->
                val minutos = slider.progress + 1
                service.setSleepTimer(minutos)
                mensaje("Se apagará en ${textoMinutos(minutos)}")
            }
            .setNegativeButton("Cancelar", null)

        // El botón neutral solo aparece si ya hay un temporizador activo
        if (service.sleepEndTime > 0) {
            builder.setNeutralButton("Quitar temporizador") { _, _ ->
                service.cancelSleepTimer()
                mensaje("Temporizador cancelado")
            }
        }
        builder.show()
    }

    private fun textoMinutos(m: Int): String {
        val h = m / 60
        val r = m % 60
        return when {
            h == 0 -> if (r == 1) "1 minuto" else "$r minutos"
            r == 0 -> if (h == 1) "1 hora" else "$h horas"
            else -> "$h h $r min"
        }
    }

    // ---------- Eliminar canción ----------
    fun confirmarEliminar(song: Song, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("¿Eliminar canción?")
            .setMessage(
                "\"${SongStore.displayTitle(activity, song)}\" se borrará de tu teléfono " +
                        "y no se puede deshacer."
            )
            .setPositiveButton("Eliminar") { _, _ -> eliminar(song, onDone) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun eliminar(song: Song, onDone: () -> Unit) {
        deleteSong = song
        deleteDone = onDone
        val uri = uriDe(song)
        deleteUri = uri
        if (uri == null) {
            borrarArchivo(song)
            return
        }
        try {
            // Los archivos creados por la app (descargas y extracciones) se borran directo
            val filas = activity.contentResolver.delete(uri, null, null)
            if (filas > 0) terminarBorrado(song) else borrarArchivo(song)
        } catch (e: SecurityException) {
            // Archivos de otras apps: el sistema pide permiso al usuario
            val sender = intentSenderPara(uri, e)
            if (sender != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                borrarArchivo(song)
            }
        }
    }

    private fun intentSenderPara(uri: Uri, e: SecurityException): IntentSender? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return MediaStore.createDeleteRequest(activity.contentResolver, listOf(uri)).intentSender
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
            return e.userAction.actionIntent.intentSender
        }
        return null
    }

    // Último recurso (Android 9 o menos, o archivos que no están en MediaStore)
    private fun borrarArchivo(song: Song) {
        val ok = try {
            File(song.path).delete()
        } catch (e: Exception) {
            false
        }
        if (ok) {
            MediaScannerConnection.scanFile(activity, arrayOf(song.path), null, null)
            terminarBorrado(song)
        } else {
            mensaje("No se pudo eliminar este archivo")
            limpiarBorrado()
        }
    }

    private fun terminarBorrado(song: Song) {
        SongStore.forget(activity, song)   // olvida título, artista e imagen guardados
        onDeleted(song)                    // la saca de la cola del reproductor
        val done = deleteDone
        limpiarBorrado()
        mensaje("Canción eliminada")
        done?.invoke()                     // la pantalla vuelve a leer la lista
    }

    private fun limpiarBorrado() {
        deleteSong = null
        deleteUri = null
        deleteDone = null
    }

    private fun uriDe(song: Song): Uri? {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        activity.contentResolver.query(
            base,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DATA} = ?",
            arrayOf(song.path),
            null
        )?.use {
            if (it.moveToFirst()) return ContentUris.withAppendedId(base, it.getLong(0))
        }
        return null
    }

    private fun mensaje(texto: String) {
        Snackbar.make(
            activity.findViewById(android.R.id.content), texto, Snackbar.LENGTH_SHORT
        ).show()
    }
}