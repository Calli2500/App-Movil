package com.example.cuicatl

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
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
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText

/**
 * Pantalla principal de CUICATL.
 *
 * Qué hace esta clase (de arriba hacia abajo):
 *  1. Muestra la biblioteca en 3 pestañas: MP3, Videos y Playlists.
 *  2. Lee del teléfono (MediaStore) los MP3 y los videos MP4; cualquier otro formato se ignora.
 *  3. Maneja el menú lateral, la búsqueda, el orden y el mini reproductor.
 *  4. Pide los permisos de lectura de audio y video.
 *  5. Permite renombrar y ocultar videos (solo dentro de la app, ver [VideoPrefs]).
 *
 * Implementa dos interfaces para que los fragments de las pestañas le pidan cosas:
 *  - [LibraryHost]: clics sobre canciones (pestaña MP3).
 *  - [VideoHost]: clics sobre videos (pestaña Videos).
 */
class MainActivity : AppCompatActivity(), LibraryHost, VideoHost {

    // =====================================================================
    // CONSTANTES Y TIPOS
    // =====================================================================

    companion object {
        /** Pestaña que se abre al entrar: 0 = MP3, 1 = Videos, 2 = Playlists. */
        const val EXTRA_TAB = "tab"
        private const val TAG = "CuicatlLifecycle"
        private const val REQ_AUDIO = 100 // código de la solicitud de permisos (audio + video)
        private const val PREFS = "library_prefs"
        private const val KEY_ORDEN = "orden"
    }

    /** Criterios de orden disponibles en el Spinner. */
    private enum class Orden(val etiqueta: String) {
        NOMBRE_AZ("Nombre (A–Z)"),
        NOMBRE_ZA("Nombre (Z–A)"),
        RECIENTES("Más recientes"),
        ARTISTA("Artista") // solo aplica a MP3: los videos no tienen artista
    }

    // =====================================================================
    // ESTADO
    // =====================================================================

    // --- Servicio de música (reproduce en segundo plano) ---
    private var musicService: MusicService? = null
    private var serviceBound = false
    private var pendingPlay: Pair<List<Song>, Int>? = null // canción pedida antes de conectar el servicio
    private lateinit var optionsHelper: SongOptionsHelper
    private val uiHandler = Handler(Looper.getMainLooper())

    // --- Lo que hay en el teléfono, SIN filtrar ni ordenar ---
    private var allMp3: List<Song> = emptyList()
    private var allVideos: List<VideoItem> = emptyList()      // videos visibles (sin los ocultos)
    private var videosOcultos: List<VideoItem> = emptyList()  // videos que el usuario ocultó
    private val dateAdded = mutableMapOf<String, Long>()      // ruta del MP3 -> fecha en que se agregó

    // --- Filtros de la lista ---
    private var orden = Orden.NOMBRE_AZ
    private var consulta = ""
    private lateinit var svSearch: SearchView
    private lateinit var tabLayout: TabLayout
    private val vm: LibraryViewModel by viewModels() // comparte las listas con los fragments

    // El botón "atrás" cierra primero la búsqueda (solo está activo mientras se ve)
    private val cerrarBusquedaCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cerrarBusqueda()
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sin acción */ }

    // Se ejecuta cuando el servicio avisa que cambió la canción o el estado de reproducción
    private val serviceListener: () -> Unit = { actualizarMiniPlayer() }

    // Actualiza la barra de progreso del mini reproductor cada 500 ms
    private val progressRunnable = object : Runnable {
        override fun run() {
            actualizarProgresoMini()
            uiHandler.postDelayed(this, 500)
        }
    }

    // Conexión con el servicio de música
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.LocalBinder).getService()
            musicService?.addListener(serviceListener)
            val pendiente = pendingPlay
            if (pendiente != null) {
                // El usuario tocó una canción antes de que el servicio estuviera listo
                pendingPlay = null
                musicService?.playQueue(pendiente.first, pendiente.second)
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java))
            } else {
                // Si no suena nada, retoma la última canción (en pausa, donde se quedó)
                musicService?.restaurarUltima()
            }
            actualizarMiniPlayer()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    // =====================================================================
    // CICLO DE VIDA
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.aplicar(this)
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity.onCreate")
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        optionsHelper = SongOptionsHelper(this) { song -> musicService?.removeSong(song.path) }

        // Orden guardado la última vez
        val guardado = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_ORDEN, 0)
        orden = Orden.entries.getOrElse(guardado) { Orden.NOMBRE_AZ }

        // --- Ajuste de márgenes del sistema (barra de estado y de navegación) ---
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

        // --- Menú lateral (drawer) ---
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        DrawerHeader.enlazar(drawerLayout, navView) // nombre del usuario en el encabezado
        val btnMenu = findViewById<ImageView>(R.id.btnMenu)

        btnMenu.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_inicio -> drawerLayout.closeDrawer(GravityCompat.START)
                R.id.nav_favoritos -> {
                    startActivity(Intent(this, FavoritesActivity::class.java))
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
                R.id.nav_playlists -> {
                    findViewById<ViewPager2>(R.id.pagerLibrary).currentItem = 2
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
                R.id.nav_videos_ocultos -> {
                    mostrarVideosOcultos()
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
                R.id.nav_cuenta -> startActivity(Intent(this, AccountActivity::class.java))
                R.id.nav_tema -> ThemeHelper.mostrarDialogo(this)
                R.id.nav_acerca -> startActivity(Intent(this, AboutActivity::class.java))
            }
            true
        }

        // --- Pestañas: TabLayout + ViewPager2 con un fragment por pestaña (Lesson 9) ---
        tabLayout = findViewById(R.id.tabLayout)
        val pager = findViewById<ViewPager2>(R.id.pagerLibrary)
        pager.adapter = LibraryPagerAdapter(supportFragmentManager, lifecycle)
        pager.offscreenPageLimit = 1
        TabLayoutMediator(tabLayout, pager) { tab, position ->
            tab.text = getString(
                when (position) {
                    0 -> R.string.tab_mp3
                    1 -> R.string.tab_videos
                    else -> R.string.tab_playlists
                }
            )
        }.attach()
        // Si venimos del menú de otra pantalla con "Playlists", se abre esa pestaña
        pager.setCurrentItem(intent.getIntExtra(EXTRA_TAB, 0).coerceIn(0, 2), false)

        // --- Búsqueda (SearchView) y orden (Spinner) ---
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

        // --- Controles del mini reproductor ---
        findViewById<View>(R.id.miniPlayer).setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        findViewById<ImageView>(R.id.btnMiniPrev).setOnClickListener { musicService?.previous() }
        findViewById<ImageView>(R.id.btnMiniPlay).setOnClickListener { musicService?.togglePlayPause() }
        findViewById<ImageView>(R.id.btnMiniNext).setOnClickListener { musicService?.next() }
        findViewById<ImageView>(R.id.btnMiniClose).setOnClickListener { musicService?.stopMusic() }

        // --- Permisos ---
        // Si ya hay permiso, las listas se cargan en onResume; si no, se piden aquí
        if (!tienePermisoAudio() || !tienePermisoVideo()) {
            ActivityCompat.requestPermissions(this, permisosBiblioteca(), REQ_AUDIO)
        }

        // Notificaciones (necesarias para el control de música en Android 13+)
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

    // Cada vez que se vuelve a esta pantalla (de editar, descargar o extraer) las listas se releen
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity.onResume")
        if (tienePermisoAudio()) cargarCancionesLocales()
        if (tienePermisoVideo()) cargarVideos()
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

    // =====================================================================
    // BÚSQUEDA Y ORDEN
    // =====================================================================

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
        // El color del texto seleccionado se pone aquí (el textColor del XML no afecta al ArrayAdapter)
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, Orden.entries.map { it.etiqueta }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(ContextCompat.getColor(context, R.color.text_dark))
                return v
            }
        }
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

    /** Ordena los MP3 según el criterio elegido en el Spinner. */
    private fun ordenar(songs: List<Song>): List<Song> = when (orden) {
        Orden.NOMBRE_AZ -> songs.sortedBy { SongStore.displayTitle(this, it).lowercase() }
        Orden.NOMBRE_ZA -> songs.sortedByDescending { SongStore.displayTitle(this, it).lowercase() }
        Orden.RECIENTES -> songs.sortedByDescending { dateAdded[it.path] ?: 0L }
        Orden.ARTISTA -> songs.sortedWith(
            compareBy({ SongStore.displayArtist(this, it).lowercase() }, { SongStore.displayTitle(this, it).lowercase() })
        )
    }

    /** ¿El MP3 coincide con el texto buscado? (título, título original o artista) */
    private fun coincide(song: Song): Boolean {
        if (consulta.isEmpty()) return true
        val q = consulta.lowercase()
        return SongStore.displayTitle(this, song).lowercase().contains(q) ||
                song.originalTitle.lowercase().contains(q) ||
                SongStore.displayArtist(this, song).lowercase().contains(q)
    }

    /** ¿El video coincide con el texto buscado? (solo por título) */
    private fun coincideVideo(video: VideoItem): Boolean =
        consulta.isEmpty() || video.title.lowercase().contains(consulta.lowercase())

    /** Ordena los videos. Como no tienen artista, "Artista" se trata como A–Z. */
    private fun ordenarVideos(videos: List<VideoItem>): List<VideoItem> = when (orden) {
        Orden.NOMBRE_ZA -> videos.sortedByDescending { it.title.lowercase() }
        Orden.RECIENTES -> videos.sortedByDescending { it.dateAdded }
        else -> videos.sortedBy { it.title.lowercase() }
    }

    // =====================================================================
    // MENSAJES (Snackbar)
    // =====================================================================

    /** Muestra un mensaje inferior, con un botón de acción opcional (por ejemplo "DESHACER"). */
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
                .setActionTextColor(ContextCompat.getColor(this, R.color.snack_action))
        }
        snack.show()
    }

    // =====================================================================
    // MINI REPRODUCTOR
    // =====================================================================

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

    // =====================================================================
    // PERMISOS
    // Android 13+ separa los permisos por tipo (audio / video).
    // En Android 12 o menos existe un solo permiso de almacenamiento.
    // =====================================================================

    private fun permisoAudio(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun tienePermisoAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, permisoAudio()) == PackageManager.PERMISSION_GRANTED

    private fun permisoVideo(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun tienePermisoVideo(): Boolean =
        ContextCompat.checkSelfPermission(this, permisoVideo()) == PackageManager.PERMISSION_GRANTED

    /** Audio y video a la vez (en Android 12 o menos es un solo permiso de almacenamiento). */
    private fun permisosBiblioteca(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** Respuesta del usuario al cuadro de permisos: carga lo que ya se pueda leer. */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return

        if (tienePermisoAudio()) cargarCancionesLocales()
        if (tienePermisoVideo()) cargarVideos()

        // Sin permiso de audio no hay música que mostrar: se avisa y se ofrece ir a Ajustes
        if (!tienePermisoAudio()) {
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

    // =====================================================================
    // LECTURA DEL TELÉFONO (MediaStore)
    // Primero se lee todo y se guarda sin filtrar; luego mostrarLista() lo dibuja.
    // =====================================================================

    /** Lee los MP3 del teléfono. Cualquier otro formato de audio se ignora. */
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

                // Solo MP3: cualquier otro formato de audio se ignora
                if (mimeType.contains("audio/mpeg") || path.endsWith(".mp3", ignoreCase = true)) {
                    mp3Songs.add(Song(path, title, artist))
                    dateAdded[path] = it.getLong(dateCol)
                }
            }
        }

        allMp3 = mp3Songs
        mostrarLista()
    }

    /**
     * Lee los videos MP4 del teléfono (los demás formatos de video se ignoran).
     * Separa los visibles de los ocultos y aplica los nombres personalizados guardados en [VideoPrefs].
     */
    private fun cargarVideos() {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED
        )
        val ocultos = VideoPrefs.ocultos(this)
        val visibles = mutableListOf<VideoItem>()
        val escondidos = mutableListOf<VideoItem>()

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.MIME_TYPE} = ?", // solo MP4
            arrayOf("video/mp4"),
            null
        )?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val durCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val original = it.getString(titleCol) ?: "Video"
                val video = VideoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    title = VideoPrefs.nombre(this, id) ?: original, // nombre personalizado, si existe
                    durationMs = it.getLong(durCol),
                    dateAdded = it.getLong(dateCol),
                    originalTitle = original
                )
                if (id in ocultos) escondidos.add(video) else visibles.add(video)
            }
        }

        allVideos = visibles
        videosOcultos = escondidos
        mostrarLista()
    }

    // =====================================================================
    // DIBUJAR LAS LISTAS
    // =====================================================================

    /**
     * Aplica búsqueda y orden a lo leído del teléfono, se lo pasa a los fragments
     * (a través del ViewModel) y actualiza los contadores de las pestañas.
     */
    private fun mostrarLista() {
        val mp3 = ordenar(allMp3.filter { coincide(it) })
        val videos = ordenarVideos(allVideos.filter { coincideVideo(it) })

        vm.consulta = consulta
        vm.mp3.value = mp3
        vm.videos.value = videos

        tabLayout.getTabAt(0)?.text = getString(
            R.string.tab_count, getString(R.string.tab_mp3), cuenta(mp3.size, allMp3.size)
        )
        tabLayout.getTabAt(1)?.text = getString(
            R.string.tab_count, getString(R.string.tab_videos), cuenta(videos.size, allVideos.size)
        )
    }

    /** Texto del contador de la pestaña: "5" normalmente, "2/5" mientras se busca. */
    private fun cuenta(mostrados: Int, total: Int): String =
        if (consulta.isNotEmpty()) "$mostrados/$total" else "$mostrados"

    // =====================================================================
    // LibraryHost: lo que piden los fragments de la pestaña MP3
    // =====================================================================

    override fun onSongClick(songs: List<Song>, index: Int) {
        val service = musicService
        if (service == null) {
            // El servicio aún no está conectado: se guarda la petición y se atiende al conectar
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

    // =====================================================================
    // VideoHost: lo que piden los fragments de la pestaña Videos
    // =====================================================================

    /** Toque corto: abre el video a pantalla completa. */
    override fun onVideoClick(video: VideoItem) {
        // La música se pausa mientras se ve el video
        if (musicService?.isPlaying == true) musicService?.togglePlayPause()
        startActivity(
            Intent(this, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerActivity.EXTRA_URI, video.uri.toString())
                .putExtra(VideoPlayerActivity.EXTRA_TITLE, video.title)
        )
    }

    /** Toque largo: menú con Renombrar, Restaurar nombre (si fue renombrado) y Ocultar. */
    override fun onVideoLongClick(video: VideoItem) {
        val renombrado = video.title != video.originalTitle

        // Cada opción es un par: (texto que se muestra, acción que se ejecuta)
        val opciones = mutableListOf<Pair<String, () -> Unit>>()
        opciones.add(getString(R.string.video_opt_rename) to { pedirNombreVideo(video) })
        if (renombrado) {
            opciones.add(getString(R.string.video_opt_restore) to {
                VideoPrefs.restaurarNombre(this, video.id)
                cargarVideos()
            })
        }
        opciones.add(getString(R.string.video_opt_hide) to {
            VideoPrefs.ocultar(this, video.id)
            cargarVideos()
            // Aviso con opción de deshacer
            mostrarMensaje(
                getString(R.string.video_hidden_msg),
                Snackbar.LENGTH_LONG,
                getString(R.string.video_undo) to {
                    VideoPrefs.mostrar(this, video.id)
                    cargarVideos()
                }
            )
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(video.title)
            .setItems(opciones.map { it.first }.toTypedArray()) { _, i -> opciones[i].second() }
            .show()
    }

    /** Diálogo con un campo de texto para escribir el nuevo nombre del video. */
    private fun pedirNombreVideo(video: VideoItem) {
        val campo = TextInputEditText(this).apply {
            setText(video.title)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
        }
        // Contenedor con margen para que el campo no quede pegado a los bordes del diálogo
        val margen = (24 * resources.displayMetrics.density).toInt()
        val contenedor = FrameLayout(this).apply {
            setPadding(margen, margen / 2, margen, 0)
            addView(campo)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.video_rename_title)
            .setView(contenedor)
            .setPositiveButton(R.string.acct_btn_save) { _, _ ->
                val nuevo = campo.text?.toString().orEmpty().trim()
                // Si lo dejan vacío o igual al original, se vuelve al nombre del archivo
                if (nuevo.isEmpty() || nuevo == video.originalTitle) {
                    VideoPrefs.restaurarNombre(this, video.id)
                } else {
                    VideoPrefs.renombrar(this, video.id, nuevo)
                }
                cargarVideos()
            }
            .setNegativeButton(R.string.acct_cancel, null)
            .show()
    }

    /** Diálogo (desde el menú lateral) para volver a mostrar videos que se habían ocultado. */
    private fun mostrarVideosOcultos() {
        val ocultos = videosOcultos
        if (ocultos.isEmpty()) {
            mostrarMensaje(getString(R.string.video_hidden_none))
            return
        }
        val marcados = BooleanArray(ocultos.size) // qué videos marcó el usuario para mostrar
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.video_hidden_title)
            .setMultiChoiceItems(ocultos.map { it.title }.toTypedArray(), marcados) { _, i, check ->
                marcados[i] = check
            }
            .setPositiveButton(R.string.video_show) { _, _ ->
                ocultos.forEachIndexed { i, v -> if (marcados[i]) VideoPrefs.mostrar(this, v.id) }
                cargarVideos()
            }
            .setNegativeButton(R.string.acct_cancel, null)
            .show()
    }
}