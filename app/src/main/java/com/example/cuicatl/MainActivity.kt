package com.example.cuicatl

import android.Manifest
import android.content.ComponentName
import android.content.Context
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
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

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
    private lateinit var etSearch: EditText

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

        optionsHelper = SongOptionsHelper(this)

        // Orden guardado
        val guardado = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ORDEN, 0)
        orden = Orden.values().getOrElse(guardado) { Orden.NOMBRE_AZ }

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

        // Búsqueda y orden
        etSearch = findViewById(R.id.etLibrarySearch)
        onBackPressedDispatcher.addCallback(this, cerrarBusquedaCallback)
        findViewById<ImageView>(R.id.btnSearch).setOnClickListener { alternarBusqueda() }
        findViewById<TextView>(R.id.btnSort).setOnClickListener { mostrarOrdenes() }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                consulta = s?.toString()?.trim() ?: ""
                mostrarLista()
            }
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                ocultarTeclado()
                true
            } else false
        }

        // Controles del mini reproductor
        findViewById<View>(R.id.miniPlayer).setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        findViewById<ImageView>(R.id.btnMiniPrev).setOnClickListener { musicService?.previous() }
        findViewById<ImageView>(R.id.btnMiniPlay).setOnClickListener { musicService?.togglePlayPause() }
        findViewById<ImageView>(R.id.btnMiniNext).setOnClickListener { musicService?.next() }

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
            Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE
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
        if (etSearch.visibility == View.VISIBLE) cerrarBusqueda() else abrirBusqueda()
    }

    private fun abrirBusqueda() {
        etSearch.visibility = View.VISIBLE
        etSearch.requestFocus()
        getSystemService(InputMethodManager::class.java)
            .showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        cerrarBusquedaCallback.isEnabled = true
    }

    private fun cerrarBusqueda() {
        etSearch.setText("") // al quedar vacío, la lista vuelve a mostrarse completa
        etSearch.visibility = View.GONE
        ocultarTeclado()
        cerrarBusquedaCallback.isEnabled = false
    }

    private fun ocultarTeclado() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun mostrarOrdenes() {
        val opciones = Orden.values()
        AlertDialog.Builder(this)
            .setTitle("Ordenar por")
            .setSingleChoiceItems(
                opciones.map { it.etiqueta }.toTypedArray(), orden.ordinal
            ) { dialog, which ->
                orden = opciones[which]
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_ORDEN, which).apply()
                mostrarLista()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun ordenar(songs: List<Song>): List<Song> = when (orden) {
        Orden.NOMBRE_AZ -> songs.sortedBy { SongStore.displayTitle(this, it).lowercase() }
        Orden.NOMBRE_ZA -> songs.sortedByDescending { SongStore.displayTitle(this, it).lowercase() }
        Orden.RECIENTES -> songs.sortedByDescending { dateAdded[it.path] ?: 0L }
        Orden.ARTISTA -> songs.sortedWith(
            compareBy({ it.artist.lowercase() }, { SongStore.displayTitle(this, it).lowercase() })
        )
    }

    private fun coincide(song: Song): Boolean {
        if (consulta.isEmpty()) return true
        val q = consulta.lowercase()
        return SongStore.displayTitle(this, song).lowercase().contains(q) ||
                song.originalTitle.lowercase().contains(q) ||
                song.artist.lowercase().contains(q)
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
        if (mini.visibility == View.VISIBLE) snack.setAnchorView(mini)
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

    // Aplica búsqueda y orden y dibuja las dos secciones
    private fun mostrarLista() {
        val containerMp3 = findViewById<LinearLayout>(R.id.containerMp3)
        val containerOthers = findViewById<LinearLayout>(R.id.containerOthers)
        containerMp3.removeAllViews()
        containerOthers.removeAllViews()

        val mp3 = ordenar(allMp3.filter { coincide(it) })
        val otros = ordenar(allOthers.filter { coincide(it) })

        agregarVistas(mp3, containerMp3, "MP3")
        agregarVistas(otros, containerOthers, "OTRO")

        findViewById<TextView>(R.id.tvCountMp3).text = contador(mp3.size, allMp3.size)
        findViewById<TextView>(R.id.tvCountOther).text = contador(otros.size, allOthers.size)
        findViewById<TextView>(R.id.btnSort).text = "ORDEN: ${orden.etiqueta.uppercase()}"
    }

    private fun contador(mostrados: Int, total: Int): String {
        val base = if (mostrados == 1) "1 archivo" else "$mostrados archivos"
        return if (consulta.isNotEmpty()) "$mostrados de $total" else base
    }

    private fun agregarVistas(songs: List<Song>, container: LinearLayout, formato: String) {
        if (songs.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (consulta.isEmpty()) "Sin archivos" else "Sin resultados"
                setTextColor(android.graphics.Color.parseColor("#666666"))
                textSize = 13f
                val p = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, p, 0, p)
            })
            return
        }

        val inflater = LayoutInflater.from(this)

        songs.forEachIndexed { index, song ->
            val songView = inflater.inflate(R.layout.item_song, container, false)

            val tvTitle = songView.findViewById<TextView>(R.id.tvSongTitle)
            val tvArtist = songView.findViewById<TextView>(R.id.tvSongArtist)
            val tvFormat = songView.findViewById<TextView>(R.id.tvSongFormat)

            tvTitle.text = SongStore.displayTitle(this, song)
            tvArtist.text = song.artist
            tvFormat.text = formato

            // Tocar la canción: manda la lista (como se ve en pantalla) al servicio y abre el reproductor
            songView.setOnClickListener {
                val service = musicService
                if (service == null) {
                    pendingPlay = songs to index
                    mostrarMensaje("Conectando reproductor…")
                } else {
                    service.playQueue(songs, index)
                    startActivity(Intent(this, PlayerActivity::class.java))
                }
            }

            // Los 3 puntos de cada canción
            songView.findViewById<View>(R.id.btnSongOptions)?.setOnClickListener { anchor ->
                optionsHelper.showSongMenu(anchor, song) {
                    mostrarLista() // el título pudo cambiar, y con él el orden
                    actualizarMiniPlayer()
                }
            }

            container.addView(songView)
        }
    }
}