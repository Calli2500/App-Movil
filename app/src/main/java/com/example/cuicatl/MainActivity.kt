package com.example.cuicatl

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity(), LibraryHost {

    private companion object {
        const val TAG = "CuicatlLifecycle"
        const val REQ_AUDIO = 100
        const val PREFS = "library_prefs"
        const val KEY_ORDEN = "orden"
    }

    private enum class Orden(val etiqueta: String) {
        NOMBRE_AZ("Nombre (A–Z)"),
        NOMBRE_ZA("Nombre (Z–A)"),
        RECIENTES("Más recientes"),
        ARTISTA("Artista")
    }

    private var musicService: MusicService? = null
    private var serviceBound = false
    private var pendingPlay: Pair<List<Song>, Int>? = null
    private lateinit var optionsHelper: SongOptionsHelper
    private val uiHandler = Handler(Looper.getMainLooper())

    // Lo que hay en el teléfono (sin filtrar) y la fecha en que se agregó cada archivo
    private var allMp3: List<Song> = emptyList()
    private var allOthers: List<Song> = emptyList()
    private val dateAdded = mutableMapOf<String, Long>()

    private var orden = Orden.NOMBRE_AZ
    private var consulta = ""
    private lateinit var svSearch: SearchView
    private lateinit var tabLayout: TabLayout
    private val vm: LibraryViewModel by viewModels()

    // El botón "atrás" cierra primero la búsqueda (solo está activo mientras se ve)
    private val cerrarBusquedaCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cerrarBusqueda()
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sin acción */ }

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

    // ---------- CICLO DE VIDA ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity.onCreate")
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        optionsHelper = SongOptionsHelper(this) { song -> musicService?.removeSong(song.path) }
        // Orden guardado
        val guardado = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_ORDEN, 0)
        orden = Orden.entries.getOrElse(guardado) { Orden.NOMBRE_AZ }

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
                R.id.nav_favoritos -> {
                    startActivity(Intent(this, FavoritesActivity::class.java))
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
                R.id.nav_descargas -> {
                    startActivity(Intent(this, DownloadActivity::class.java))
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
                R.id.nav_reproductor -> {
                    startActivity(Intent(this, PlayerActivity::class.java))
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
                R.id.nav_acerca -> startActivity(Intent(this, AboutActivity::class.java))
            }
            true
        }

        // Pestañas: TabLayout + ViewPager2 con un fragment por pestaña (Lesson 9)
        tabLayout = findViewById(R.id.tabLayout)
        val pager = findViewById<ViewPager2>(R.id.pagerLibrary)
        pager.adapter = LibraryPagerAdapter(supportFragmentManager, lifecycle)
        pager.offscreenPageLimit = 1
        TabLayoutMediator(tabLayout, pager) { tab, position ->
            tab.text = getString(if (position == 0) R.string.tab_mp3 else R.string.tab_other)
        }.attach()

        // Búsqueda (SearchView) y orden (Spinner)
        svSearch = findViewById(R.id.svLibrary)
        onBackPressedDispatcher.addCallback(this, cerrarBusquedaCallback)
        findViewById<ImageView>(R.id.btnSearch).setOnClickListener { alternarBusqueda() }
        svSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                svSearch.clearFocus()
                ocultarTeclado()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                consulta = newText?.trim() ?: ""
                mostrarLista()
                return true
            }
        })
        configurarSpinnerOrden()

        // Controles del mini reproductor
        findViewById<View>(R.id.miniPlayer).setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        findViewById<ImageView>(R.id.btnMiniPrev).setOnClickListener { musicService?.previous() }
        findViewById<ImageView>(R.id.btnMiniPlay).setOnClickListener { musicService?.togglePlayPause() }
        findViewById<ImageView>(R.id.btnMiniNext).setOnClickListener { musicService?.next() }
        findViewById<ImageView>(R.id.btnMiniClose).setOnClickListener { musicService?.stopMusic() }

        // Si ya hay permiso, la lista se carga en onResume; si no, se pide aquí
        if (!tienePermisoAudio()) {
            ActivityCompat.requestPermissions(this, arrayOf(permisoAudio()), REQ_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "MainActivity.onStart")
        serviceBound = bindService(
            Intent(this, MusicService::class.java), connection, BIND_AUTO_CREATE
        )
        uiHandler.post(progressRunnable)
    }

    // Cada vez que se vuelve a esta pantalla (de editar, descargar o extraer) la lista se relee
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity.onResume")
        if (tienePermisoAudio()) cargarCancionesLocales()
        actualizarMiniPlayer()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity.onPause")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "MainActivity.onStop")
        uiHandler.removeCallbacks(progressRunnable)
        musicService?.removeListener(serviceListener)
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        musicService = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity.onDestroy")
    }

    // ---------- BÚSQUEDA Y ORDEN ----------
    private fun alternarBusqueda() {
        if (svSearch.isVisible) cerrarBusqueda() else abrirBusqueda()
    }

    private fun abrirBusqueda() {
        svSearch.visibility = View.VISIBLE
        svSearch.requestFocus()
        svSearch.post {
            WindowCompat.getInsetsController(window, svSearch).show(WindowInsetsCompat.Type.ime())
        }
        cerrarBusquedaCallback.isEnabled = true
    }

    private fun cerrarBusqueda() {
        svSearch.setQuery("", false) // al quedar vacío, la lista vuelve a mostrarse completa
        svSearch.visibility = View.GONE
        ocultarTeclado()
        cerrarBusquedaCallback.isEnabled = false
    }

    private fun ocultarTeclado() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(svSearch.windowToken, 0)
    }

    // Spinner de orden: ArrayAdapter + OnItemSelectedListener (Lesson 9)
    private fun configurarSpinnerOrden() {
        val spinner = findViewById<Spinner>(R.id.spOrden)
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, Orden.entries.map { it.etiqueta }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(orden.ordinal, false) // sin disparar el listener al iniciar
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val nuevo = Orden.entries[position]
                if (nuevo == orden) return
                orden = nuevo
                getSharedPreferences(PREFS, MODE_PRIVATE).edit { putInt(KEY_ORDEN, position) }
                mostrarLista()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun ordenar(songs: List<Song>): List<Song> = when (orden) {
        Orden.NOMBRE_AZ -> songs.sortedBy { SongStore.displayTitle(this, it).lowercase() }
        Orden.NOMBRE_ZA -> songs.sortedByDescending { SongStore.displayTitle(this, it).lowercase() }
        Orden.RECIENTES -> songs.sortedByDescending { dateAdded[it.path] ?: 0L }
        Orden.ARTISTA -> songs.sortedWith(
            compareBy({ SongStore.displayArtist(this, it).lowercase() }, { SongStore.displayTitle(this, it).lowercase() })
        )
    }

    private fun coincide(song: Song): Boolean {
        if (consulta.isEmpty()) return true
        val q = consulta.lowercase()
        return SongStore.displayTitle(this, song).lowercase().contains(q) ||
                song.originalTitle.lowercase().contains(q) ||
                SongStore.displayArtist(this, song).lowercase().contains(q)
    }

    // ---------- MENSAJES ----------
    private fun mostrarMensaje(
        texto: String,
        duracion: Int = Snackbar.LENGTH_SHORT,
        accion: Pair<String, () -> Unit>? = null
    ) {
        val snack = Snackbar.make(findViewById(R.id.content_root), texto, duracion)
        // Si el mini reproductor está visible, el mensaje aparece encima de él
        val mini = findViewById<View>(R.id.miniPlayer)
        if (mini.isVisible) snack.setAnchorView(mini)
        if (accion != null) {
            snack.setAction(accion.first) { accion.second() }
                .setActionTextColor(ContextCompat.getColor(this, R.color.bg_mint))
        }
        snack.show()
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
        findViewById<TextView>(R.id.tvMiniArtist).text = SongStore.displayArtist(this, song)
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

    // ---------- PERMISOS ----------
    private fun permisoAudio(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun tienePermisoAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, permisoAudio()) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return

        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            cargarCancionesLocales()
        } else {
            mostrarMensaje(
                "Sin permiso de audio no puedo mostrar tu música",
                Snackbar.LENGTH_LONG,
                "AJUSTES" to {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                }
            )
        }
    }

    // ---------- LISTA DE CANCIONES ----------
    // Lee el teléfono (MediaStore) y guarda todo sin filtrar; luego se dibuja con mostrarLista()
    private fun cargarCancionesLocales() {
        val projection = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED
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
        dateAdded.clear()

        cursor?.use {
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (it.moveToNext()) {
                val title = it.getString(titleCol) ?: "Desconocido"
                val artist = it.getString(artistCol) ?: "Desconocido"
                val path = it.getString(dataCol) ?: ""
                val mimeType = it.getString(mimeCol) ?: ""
                val song = Song(path, title, artist)
                dateAdded[path] = it.getLong(dateCol)

                if (mimeType.contains("audio/mpeg") || path.endsWith(".mp3", ignoreCase = true)) {
                    mp3Songs.add(song)
                } else {
                    otherSongs.add(song)
                }
            }
        }

        allMp3 = mp3Songs
        allOthers = otherSongs
        mostrarLista()
    }

    // Aplica búsqueda y orden y se la pasa a los fragments (RecyclerView) por el ViewModel
    private fun mostrarLista() {
        val mp3 = ordenar(allMp3.filter { coincide(it) })
        val otros = ordenar(allOthers.filter { coincide(it) })

        vm.consulta = consulta
        vm.mp3.value = mp3
        vm.otros.value = otros

        tabLayout.getTabAt(0)?.text = getString(
            R.string.tab_count, getString(R.string.tab_mp3), cuenta(mp3.size, allMp3.size)
        )
        tabLayout.getTabAt(1)?.text = getString(
            R.string.tab_count, getString(R.string.tab_other), cuenta(otros.size, allOthers.size)
        )
    }

    private fun cuenta(mostrados: Int, total: Int): String =
        if (consulta.isNotEmpty()) "$mostrados/$total" else "$mostrados"

    // ---------- LibraryHost: lo que piden los fragments ----------
    override fun onSongClick(songs: List<Song>, index: Int) {
        val service = musicService
        if (service == null) {
            pendingPlay = songs to index
            mostrarMensaje("Conectando reproductor…")
        } else {
            service.playQueue(songs, index)
            startActivity(Intent(this, PlayerActivity::class.java))
        }
    }

    override fun onSongOptions(anchor: View, song: Song) {
        optionsHelper.showSongMenu(anchor, song) {
            cargarCancionesLocales() // el título pudo cambiar o la canción se eliminó
            actualizarMiniPlayer()
        }
    }

    override fun onFavoriteClick(song: Song): Boolean {
        val esFavorita = FavoritesManager.alternarFavorito(this, song)
        mostrarMensaje(if (esFavorita) "Agregada a favoritos" else "Eliminada de favoritos")
        return esFavorita
    }
}