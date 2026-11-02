
package com.example.cuicatl

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import androidx.core.view.GravityCompat
import android.widget.Toast
class FavoritesActivity : AppCompatActivity() {

    private lateinit var layoutFavoritesList: LinearLayout
    private lateinit var tvEmptyFavorites: TextView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnMenu: ImageView
    private lateinit var navigationView: NavigationView

    private val favoriteSongs = mutableListOf<Song>()

    private var musicService: MusicService? = null
    private var isBound = false

    // Conexión con MusicService
    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {
            val binder = service as MusicService.LocalBinder
            musicService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    // Conectar con el servicio de música
    private fun conectarServicio() {
        val intent = Intent(this, MusicService::class.java)

        isBound = bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_favorites)

        // Configuración del menú lateral
        drawerLayout = findViewById(R.id.drawerLayoutFavorites)
        navigationView = findViewById(R.id.nav_view_favorites)
        btnMenu = findViewById(R.id.btnMenu)

// Abrir menú lateral
        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

// Navegación entre pantallas
        navigationView.setNavigationItemSelectedListener { menuItem ->

            when (menuItem.itemId) {

                R.id.nav_inicio -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }

                R.id.nav_reproductor -> {
                    startActivity(Intent(this, PlayerActivity::class.java))
                    finish()
                }
            }

            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        drawerLayout = findViewById(R.id.drawerLayoutFavorites)
        btnMenu = findViewById(R.id.btnMenu)
        navigationView = findViewById(R.id.nav_view_favorites)

        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(navigationView)
        }

        // Ajustar la pantalla a las barras del sistema
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.favoritesRoot)
        ) { v, insets ->

            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }

        layoutFavoritesList = findViewById(R.id.layoutFavoritesList)
        tvEmptyFavorites = findViewById(R.id.tvEmptyFavorites)

        conectarServicio()
        cargarFavoritos()
    }

    // Desconectar el servicio al destruir la actividad
    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }

        super.onDestroy()
    }

    // Obtener las canciones guardadas como favoritas
    private fun cargarFavoritos() {

        favoriteSongs.clear()

        val rutasFavoritas = FavoritesManager.obtenerFavoritos(this)

        val projection = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )

        cursor?.use {

            val titleColumn = it.getColumnIndexOrThrow(
                MediaStore.Audio.Media.TITLE
            )

            val artistColumn = it.getColumnIndexOrThrow(
                MediaStore.Audio.Media.ARTIST
            )

            val pathColumn = it.getColumnIndexOrThrow(
                MediaStore.Audio.Media.DATA
            )

            while (it.moveToNext()) {

                val title = it.getString(titleColumn) ?: "Desconocido"
                val artist = it.getString(artistColumn) ?: "Artista desconocido"
                val path = it.getString(pathColumn) ?: continue

                if (rutasFavoritas.contains(path)) {

                    favoriteSongs.add(
                        Song(
                            path = path,
                            originalTitle = title,
                            artist = artist
                        )
                    )
                }
            }
        }

        mostrarFavoritos()
    }

    // Mostrar las canciones favoritas en pantalla
    private fun mostrarFavoritos() {

        layoutFavoritesList.removeAllViews()

        // Comprobar si existen canciones favoritas
        if (favoriteSongs.isEmpty()) {
            tvEmptyFavorites.visibility = View.VISIBLE
            return
        }

        tvEmptyFavorites.visibility = View.GONE

        favoriteSongs.forEachIndexed { index, song ->

            val itemView = layoutInflater.inflate(
                R.layout.item_song,
                layoutFavoritesList,
                false
            )

            val tvTitle = itemView.findViewById<TextView>(
                R.id.tvSongTitle
            )

            val tvArtist = itemView.findViewById<TextView>(
                R.id.tvSongArtist
            )

            val btnFavorite = itemView.findViewById<ImageButton>(
                R.id.btnFavorite
            )

            val btnOptions = itemView.findViewById<ImageView>(
                R.id.btnSongOptions
            )

            // Mostrar información de la canción
            tvTitle.text = SongStore.displayTitle(this, song)
            tvArtist.text = song.artist

            // Mostrar formato
            itemView.findViewById<TextView>(
                R.id.tvSongFormat
            ).text = "MP3"

            // Mostrar corazón activo
            btnFavorite.setImageResource(
                R.drawable.baseline_favorite_24
            )

            btnFavorite.contentDescription = "Quitar de favoritos"

            // Ocultar opciones adicionales
            btnOptions.visibility = View.GONE

            // Reproducir canción al tocar su elemento
            itemView.setOnClickListener {

                val service = musicService

                if (service != null) {

                    // Iniciar la reproducción con la lista de favoritos
                    service.playQueue(favoriteSongs.toList(), index)

                    // Abrir la pantalla del reproductor
                    val intent = Intent(this, PlayerActivity::class.java)
                    startActivity(intent)

                } else {

                    Toast.makeText(
                        this,
                        "El reproductor todavía no está disponible",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // Quitar canción de favoritos
            btnFavorite.setOnClickListener {

                FavoritesManager.alternarFavorito(this, song)

                favoriteSongs.remove(song)

                mostrarFavoritos()
            }

            layoutFavoritesList.addView(itemView)
        }
    }

    // Actualizar la lista cuando se regrese a esta pantalla
    override fun onResume() {
        super.onResume()

        if (::layoutFavoritesList.isInitialized) {
            cargarFavoritos()
        }
    }
}
