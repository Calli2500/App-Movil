package com.example.cuicatl

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import java.io.File
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.util.Log

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
                    Log.w("CuicatlDelete", "No se pudo borrar por MediaStore", e)
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
            menu.add(0, 9, 2, "Compartir")
            menu.add(0, 10, 2, "Agregar a playlist…")
            menu.add(0, 4, 3, "Eliminar canción")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> rename(song, onChanged)
                    2 -> pickImage(song, onChanged)
                    3 -> activity.startActivity(EditSongActivity.intentFor(activity, song))
                    9 -> compartir(song)
                    10 -> agregarAPlaylist(song)
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
            menu.add(0, 8, 2, "Ver cola de reproducción")
            menu.add(0, 4, 3, "Repetir canción en bucle").apply {
                isCheckable = true
                isChecked = service.loopEnabled
            }
            menu.add(0, 9, 3, "Compartir")
            menu.add(0, 10, 3, "Agregar a playlist…")
            menu.add(0, 11, 3, "Guardar fragmento A–B como MP3")
            menu.add(0, 5, 4, "Eliminar canción")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> rename(song, onChanged)
                    2 -> pickImage(song, onChanged)
                    3 -> showSleepDialog(service)
                    8 -> mostrarCola(service)
                    4 -> {
                        service.setLoop(!service.loopEnabled)
                        onChanged()
                    }
                    9 -> compartir(song)
                    10 -> agregarAPlaylist(song)
                    11 -> guardarFragmento(song, service)
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

    // ---------- Cola de reproducción ----------
    /**
     * Hoja inferior: arriba lo que suena ahora y abajo lo que viene. Tocar salta a esa canción,
     * la ✕ la quita y, manteniéndola presionada, se arrastra para cambiar su lugar.
     */
    fun mostrarCola(service: MusicService) {
        val dialog = BottomSheetDialog(activity)
        val vista = LayoutInflater.from(activity).inflate(R.layout.sheet_queue, FrameLayout(activity), false)
        val rv = vista.findViewById<RecyclerView>(R.id.rvQueue)
        val tvCount = vista.findViewById<TextView>(R.id.tvQueueCount)
        val tvNowTitle = vista.findViewById<TextView>(R.id.tvNowTitle)
        val tvNowArtist = vista.findViewById<TextView>(R.id.tvNowArtist)
        val tvEmpty = vista.findViewById<TextView>(R.id.tvQueueEmpty)
        val btnShuffle = vista.findViewById<TextView>(R.id.btnQueueShuffle)
        val btnMix = vista.findViewById<TextView>(R.id.btnQueueMix)

        // La lista solo muestra lo que viene: la fila p es la canción (actual + 1 + p) de la cola
        val adapter = QueueAdapter(
            onClick = { service.playIndex(service.currentIndex + 1 + it) },
            onRemove = { service.removeAt(service.currentIndex + 1 + it) }
        )
        rv.adapter = adapter
        rv.itemAnimator = null

        // Arrastrar para reordenar (mantener presionada una fila)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val desde = viewHolder.bindingAdapterPosition
                val hasta = target.bindingAdapterPosition
                if (desde == RecyclerView.NO_POSITION || hasta == RecyclerView.NO_POSITION) {
                    return false
                }
                adapter.mover(desde, hasta)
                val base = service.currentIndex + 1
                service.moveInQueue(base + desde, base + hasta)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }).attachToRecyclerView(rv)

        fun estiloBoton(tv: TextView, activo: Boolean) {
            val oscuro = ContextCompat.getColor(activity, R.color.text_dark)
            if (activo) {
                tv.setBackgroundColor(oscuro)
                tv.setTextColor(ContextCompat.getColor(activity, R.color.white))
            } else {
                tv.setBackgroundColor("#D3F5E7".toColorInt())
                tv.setTextColor(oscuro)
            }
        }

        btnShuffle.setOnClickListener { service.setShuffle(!service.shuffleEnabled) }
        btnMix.setOnClickListener { service.shuffleUpcoming() }

        val refrescar: () -> Unit = {
            val actual = service.currentSong
            if (actual == null) {
                dialog.dismiss() // se vació la cola o se detuvo la música
            } else {
                tvNowTitle.text = SongStore.displayTitle(activity, actual)
                tvNowArtist.text = SongStore.displayArtist(activity, actual)
                val siguientes = service.queue.drop(service.currentIndex + 1)
                adapter.submit(siguientes)
                tvEmpty.isVisible = siguientes.isEmpty()
                tvCount.text = activity.getString(R.string.queue_count, service.queue.size)
                estiloBoton(btnShuffle, service.shuffleEnabled)
            }
        }
        service.addListener(refrescar)
        dialog.setOnDismissListener { service.removeListener(refrescar) }

        dialog.setContentView(vista)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        refrescar()
        dialog.show()
    }

    // ---------- Guardar fragmento A–B como MP3 ----------
    private var recortando = false

    fun guardarFragmento(song: Song, service: MusicService) {
        if (!service.abActive) {
            mensaje("Marca primero los puntos A y B")
            return
        }
        if (recortando) {
            mensaje("Ya se está guardando otro fragmento")
            return
        }
        val a = service.loopA
        val b = service.loopB
        PlaylistDialogs.pedirNombre(
            activity, "Guardar fragmento como MP3",
            SongStore.displayTitle(activity, song) + " (fragmento)"
        ) { nombre -> recortar(song, a, b, nombre) }
    }

    private fun recortar(song: Song, a: Int, b: Int, nombre: String) {
        recortando = true
        val barra = android.widget.ProgressBar(
            activity, null, android.R.attr.progressBarStyleHorizontal
        ).apply { max = 100 }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val contenedor = FrameLayout(activity).apply {
            setPadding(pad, pad, pad, 0)
            addView(barra)
        }
        val dialogo = AlertDialog.Builder(activity)
            .setTitle("Guardando fragmento…")
            .setView(contenedor)
            .setCancelable(false)
            .show()

        Thread {
            val resultado = try {
                AudioExtractor.recortar(activity, song.path, a, b, nombre) { pct ->
                    activity.runOnUiThread { barra.progress = pct }
                }
                "Fragmento guardado en Música/Cuicatl"
            } catch (e: Exception) {
                Log.w("CuicatlClip", "No se pudo guardar el fragmento", e)
                "No se pudo guardar el fragmento"
            }
            activity.runOnUiThread {
                recortando = false
                if (!activity.isFinishing && !activity.isDestroyed) {
                    dialogo.dismiss()
                    mensaje(resultado)
                }
            }
        }.start()
    }

    // ---------- Playlists ----------
    /** Pregunta a qué playlist agregar la canción (o crea una nueva). */
    fun agregarAPlaylist(song: Song) {
        val listas = PlaylistStore.all(activity)
        val opciones = (listas.map { it.name } + "+ Nueva playlist").toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Agregar a playlist")
            .setItems(opciones) { _, i ->
                if (i < listas.size) {
                    agregar(listas[i], song)
                } else {
                    PlaylistDialogs.pedirNombre(activity, "Nueva playlist", "") { nombre ->
                        agregar(PlaylistStore.create(activity, nombre), song)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun agregar(playlist: Playlist, song: Song) {
        val agregada = PlaylistStore.addSong(activity, playlist.id, song.path)
        mensaje(
            if (agregada) "Agregada a \"${playlist.name}\""
            else "Ya estaba en \"${playlist.name}\""
        )
    }

    // ---------- Compartir ----------
    /** Manda el archivo de audio a otra app; si no está en MediaStore, comparte solo el nombre. */
    fun compartir(song: Song) {
        val titulo = SongStore.displayTitle(activity, song)
        val artista = SongStore.displayArtist(activity, song)
        val uri = uriDe(song)

        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, titulo)
            if (uri != null) {
                // El receptor obtiene permiso temporal de lectura del archivo
                type = activity.contentResolver.getType(uri) ?: "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(titulo, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.share_text, titulo, artista))
            }
        }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_title)))
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
                Log.w("CuicatlDelete", "No se pudo borrar el archivo", e)
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
        FavoritesManager.quitar(activity, song)   // la saca de favoritos si estaba
        PlaylistStore.forgetPath(activity, song.path) // la saca de las playlists
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