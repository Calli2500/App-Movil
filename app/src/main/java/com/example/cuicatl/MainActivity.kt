package com.example.cuicatl

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private var musicService: MusicService? = null
    private var serviceBound = false
    private var pendingPlay: Pair<List<Song>, Int>? = null
    private lateinit var optionsHelper: SongOptionsHelper
    private val uiHandler = Handler(Looper.getMainLooper())
    private val titleViews = mutableListOf<Pair<Song, TextView>>()

    private val serviceListener: () -> Unit = { actualizarMiniPlayer() }

    private val progressRunnable = object : Runnable {
        override fun run() {
            actualizarProgresoMini()
            uiHandler.postDelayed(this, 500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.LocalBinder).getService()
            musicService?.addListener(serviceListener)
            pendingPlay?.let { (songs, index) ->
                pendingPlay = null
                musicService?.playQueue(songs, index)
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java))
            }
            actualizarMiniPlayer()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        optionsHelper = SongOptionsHelper(this)

        // Ajuste de márgenes del sistema
        val contentRoot = findViewById<View>(R.id.content_root)
        val padL = contentRoot.paddingLeft
        val padT = contentRoot.paddingTop
        val padR = contentRoot.paddingRight
        val padB = contentRoot.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(padL + bars.left, padT + bars.top, padR + bars.right, padB + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Menú lateral
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val btnMenu = findViewById<ImageView>(R.id.btnMenu)

        btnMenu.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_inicio -> drawerLayout.closeDrawer(GravityCompat.START)
                R.id.nav_descargas -> {
                    startActivity(Intent(this, DownloadActivity::class.java))
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
                R.id.nav_reproductor -> {
                    startActivity(Intent(this, PlayerActivity::class.java))
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            }
            true
        }

        // Controles del mini reproductor
        findViewById<View>(R.id.miniPlayer).setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        findViewById<ImageView>(R.id.btnMiniPrev).setOnClickListener { musicService?.previous() }
        findViewById<ImageView>(R.id.btnMiniPlay).setOnClickListener { musicService?.togglePlayPause() }
        findViewById<ImageView>(R.id.btnMiniNext).setOnClickListener { musicService?.next() }

        solicitarPermisosYBuscarMusica()
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(
            Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        uiHandler.post(progressRunnable)
    }

    override fun onResume() {
        super.onResume()
        refrescarTitulos()
        actualizarMiniPlayer()
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(progressRunnable)
        musicService?.removeListener(serviceListener)
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        musicService = null
    }

    // ---------- MINI PLAYER ----------
    private fun actualizarMiniPlayer() {
        val mini = findViewById<View>(R.id.miniPlayer)
        val service = musicService
        val song = service?.currentSong

        if (service == null || song == null) {
            mini.visibility = View.GONE
            return
        }
        mini.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvMiniTitle).text = SongStore.displayTitle(this, song)
        findViewById<TextView>(R.id.tvMiniArtist).text = song.artist
        findViewById<ImageView>(R.id.btnMiniPlay).setImageResource(
            if (service.isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun actualizarProgresoMini() {
        val s = musicService ?: return
        val dur = s.duration
        findViewById<ProgressBar>(R.id.miniProgress).progress =
            if (dur > 0) (s.position * 100L / dur).toInt() else 0
    }

    private fun refrescarTitulos() {
        titleViews.forEach { (song, tv) -> tv.text = SongStore.displayTitle(this, song) }
    }

    // ---------- PERMISOS ----------
    private fun solicitarPermisosYBuscarMusica() {
        val permiso = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permiso) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permiso), 100)
        } else {
            cargarCancionesLocales()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            cargarCancionesLocales()
        }
    }

    // ---------- LISTA DE CANCIONES ----------
    private fun cargarCancionesLocales() {
        val containerMp3 = findViewById<LinearLayout>(R.id.containerMp3)
        val containerOthers = findViewById<LinearLayout>(R.id.containerOthers)

        containerMp3.removeAllViews()
        containerOthers.removeAllViews()
        titleViews.clear()

        val projection = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE
        )

        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )

        val mp3Songs = mutableListOf<Song>()
        val otherSongs = mutableListOf<Song>()

        cursor?.use {
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

            while (it.moveToNext()) {
                val title = it.getString(titleCol) ?: "Desconocido"
                val artist = it.getString(artistCol) ?: "Desconocido"
                val path = it.getString(dataCol) ?: ""
                val mimeType = it.getString(mimeCol) ?: ""
                val song = Song(path, title, artist)

                if (mimeType.contains("audio/mpeg") || path.endsWith(".mp3", ignoreCase = true)) {
                    mp3Songs.add(song)
                } else {
                    otherSongs.add(song)
                }
            }
        }

        agregarVistas(mp3Songs, containerMp3, "MP3")
        agregarVistas(otherSongs, containerOthers, "OTRO")
    }

    private fun agregarVistas(songs: List<Song>, container: LinearLayout, formato: String) {
        val inflater = LayoutInflater.from(this)

        songs.forEachIndexed { index, song ->
            val songView = inflater.inflate(R.layout.item_song, container, false)

            val tvTitle = songView.findViewById<TextView>(R.id.tvSongTitle)
            val tvArtist = songView.findViewById<TextView>(R.id.tvSongArtist)
            val tvFormat = songView.findViewById<TextView>(R.id.tvSongFormat)

            tvTitle.text = SongStore.displayTitle(this, song)
            tvArtist.text = song.artist
            tvFormat.text = formato
            titleViews.add(song to tvTitle)

            // Tocar la canción: manda la lista al servicio y abre el reproductor
            songView.setOnClickListener {
                val service = musicService
                if (service == null) {
                    pendingPlay = songs to index
                    Toast.makeText(this, "Conectando reproductor…", Toast.LENGTH_SHORT).show()
                } else {
                    service.playQueue(songs, index)
                    startActivity(Intent(this, PlayerActivity::class.java))
                }
            }

            // Los 3 puntos de cada canción
            songView.findViewById<View>(R.id.btnSongOptions)?.setOnClickListener { anchor ->
                optionsHelper.showSongMenu(anchor, song) {
                    refrescarTitulos()
                    actualizarMiniPlayer()
                }
            }

            container.addView(songView)
        }
    }
}