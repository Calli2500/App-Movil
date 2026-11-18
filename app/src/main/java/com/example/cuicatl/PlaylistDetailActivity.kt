package com.example.cuicatl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar

/** Canciones de una playlist: reproducir, aleatorio, quitar canciones, renombrar y eliminar. */
class PlaylistDetailActivity : AppCompatActivity(), LibraryHost {

    companion object {
        private const val EXTRA_ID = "playlist_id"

        fun intentFor(context: Context, id: String): Intent =
            Intent(context, PlaylistDetailActivity::class.java).putExtra(EXTRA_ID, id)
    }

    private lateinit var playlistId: String
    private lateinit var optionsHelper: SongOptionsHelper
    private lateinit var adapter: SongAdapter
    private lateinit var tvName: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView

    private var songs: List<Song> = emptyList()
    private var musicService: MusicService? = null
    private var serviceBound = false
    private var pendingPlay: Triple<List<Song>, Int, Boolean?>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.LocalBinder).getService()
            pendingPlay?.let { (lista, index, aleatorio) ->
                pendingPlay = null
                reproducir(lista, index, aleatorio)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_playlist_detail)
        findViewById<View>(R.id.playlistRoot).applySystemBarInsets()

        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            finish()
            return
        }
        playlistId = id

        optionsHelper = SongOptionsHelper(this)
        tvName = findViewById(R.id.tvPlaylistName)
        tvCount = findViewById(R.id.tvPlaylistCount)
        tvEmpty = findViewById(R.id.tvPlaylistEmpty)

        adapter = SongAdapter(null, this)
        val rv = findViewById<RecyclerView>(R.id.rvPlaylistSongs)
        rv.adapter = adapter
        rv.itemAnimator = null

        findViewById<View>(R.id.btnPlaylistBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPlaylistMenu).setOnClickListener { mostrarMenuPlaylist(it) }
        findViewById<View>(R.id.btnPlaylistPlay).setOnClickListener {
            if (songs.isNotEmpty()) reproducir(songs, 0, false)
        }
        findViewById<View>(R.id.btnPlaylistAdd).setOnClickListener { elegirCanciones() }
        findViewById<View>(R.id.btnPlaylistShuffle).setOnClickListener {
            if (songs.isNotEmpty()) reproducir(songs, songs.indices.random(), true)
        }
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(
            Intent(this, MusicService::class.java), connection, BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        musicService = null
    }

    // ---------- Pantalla ----------
    private fun refrescar() {
        val p = PlaylistStore.get(this, playlistId)
        if (p == null) {
            finish() // la playlist ya no existe
            return
        }
        tvName.text = p.name
        songs = cargarCanciones(p.paths)
        adapter.submitList(songs)
        tvCount.text = resources.getQuantityString(R.plurals.playlist_songs, songs.size, songs.size)
        tvEmpty.isVisible = songs.isEmpty()
    }

    // Busca en el teléfono las canciones de la playlist (las que ya no existen se omiten)
    private fun cargarCanciones(paths: List<String>): List<Song> {
        if (paths.isEmpty()) return emptyList()
        val pedidas = paths.toSet()
        val porRuta = mutableMapOf<String, Song>()
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA
            ),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )?.use {
            val titulo = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artista = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val ruta = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (it.moveToNext()) {
                val path = it.getString(ruta) ?: continue
                if (path in pedidas) {
                    porRuta[path] = Song(
                        path,
                        it.getString(titulo) ?: "Desconocido",
                        it.getString(artista) ?: "Desconocido"
                    )
                }
            }
        }
        return paths.mapNotNull { porRuta[it] }
    }

    // ---------- Agregar canciones desde la propia playlist ----------
    private fun elegirCanciones() {
        val enPlaylist = songs.map { it.path }.toSet()
        val disponibles = cargarTodas().filter { it.path !in enPlaylist }
        if (disponibles.isEmpty()) {
            mensaje("No hay más canciones para agregar")
            return
        }
        val marcadas = BooleanArray(disponibles.size)
        AlertDialog.Builder(this)
            .setTitle("Agregar canciones")
            .setMultiChoiceItems(
                disponibles.map { SongStore.displayTitle(this, it) }.toTypedArray(), marcadas
            ) { _, i, checked -> marcadas[i] = checked }
            .setPositiveButton("Agregar") { _, _ ->
                var n = 0
                disponibles.forEachIndexed { i, s ->
                    if (marcadas[i] && PlaylistStore.addSong(this, playlistId, s.path)) n++
                }
                refrescar()
                if (n > 0) mensaje("Se agregaron $n")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun cargarTodas(): List<Song> {
        val lista = mutableListOf<Song>()
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA
            ),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use {
            val titulo = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artista = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val ruta = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (it.moveToNext()) {
                val path = it.getString(ruta) ?: continue
                lista += Song(
                    path,
                    it.getString(titulo) ?: "Desconocido",
                    it.getString(artista) ?: "Desconocido"
                )
            }
        }
        return lista
    }

    private fun mensaje(texto: String) {
        Snackbar.make(findViewById(android.R.id.content), texto, Snackbar.LENGTH_SHORT).show()
    }

    // ---------- Reproducción ----------
    /** aleatorio: true/false fija el modo; null lo deja como esté. */
    private fun reproducir(lista: List<Song>, index: Int, aleatorio: Boolean?) {
        val service = musicService
        if (service == null) {
            pendingPlay = Triple(lista, index, aleatorio)
            mensaje("Conectando reproductor…")
            return
        }
        if (aleatorio != null) service.setShuffle(aleatorio)
        service.playQueue(lista, index)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ---------- Menú de la playlist ----------
    private fun mostrarMenuPlaylist(anchor: View) {
        val p = PlaylistStore.get(this, playlistId) ?: return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Cambiar nombre")
            menu.add(0, 2, 1, "Eliminar playlist")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> PlaylistDialogs.pedirNombre(
                        this@PlaylistDetailActivity, "Cambiar nombre", p.name
                    ) { nombre ->
                        PlaylistStore.rename(this@PlaylistDetailActivity, playlistId, nombre)
                        refrescar()
                    }
                    2 -> confirmarEliminar(p)
                }
                true
            }
            show()
        }
    }

    private fun confirmarEliminar(p: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar playlist?")
            .setMessage("Se borrará \"${p.name}\". Las canciones se quedan en tu teléfono.")
            .setPositiveButton("Eliminar") { _, _ ->
                PlaylistStore.delete(this, p.id)
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- LibraryHost: lo que piden las filas de canciones ----------
    override fun onSongClick(songs: List<Song>, index: Int) = reproducir(songs, index, null)

    override fun onSongOptions(anchor: View, song: Song) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Quitar de la playlist")
            menu.add(0, 2, 1, "Agregar a otra playlist…")
            menu.add(0, 3, 2, "Compartir")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> {
                        PlaylistStore.removeSong(this@PlaylistDetailActivity, playlistId, song.path)
                        refrescar()
                        mensaje("Quitada de la playlist")
                    }
                    2 -> optionsHelper.agregarAPlaylist(song)
                    3 -> optionsHelper.compartir(song)
                }
                true
            }
            show()
        }
    }

    override fun onFavoriteClick(song: Song): Boolean {
        val esFavorita = FavoritesManager.alternarFavorito(this, song)
        mensaje(if (esFavorita) "Agregada a favoritos" else "Eliminada de favoritos")
        return esFavorita
    }
}