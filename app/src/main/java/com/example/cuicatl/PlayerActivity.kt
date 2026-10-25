package com.example.cuicatl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private lateinit var gestureDetector: GestureDetector
    private lateinit var optionsHelper: SongOptionsHelper

    private lateinit var root: View
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvTimeCurrent: TextView
    private lateinit var tvTimeTotal: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnRepeat: ImageView
    private lateinit var ivAlbumArt: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var btnSpeed: TextView
    private lateinit var btnLoopA: TextView
    private lateinit var btnLoopB: TextView
    private lateinit var btnClearAB: TextView

    private var musicService: MusicService? = null
    private var serviceBound = false
    private var userSeeking = false
    private var hadSong = false
    private var ignoreSwipe = false
    private var lastCoverPath: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    private val serviceListener: () -> Unit = { actualizarUI() }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val s = musicService
            if (s != null && s.currentSong != null && !userSeeking) {
                seekBar.max = s.duration
                seekBar.progress = s.position
                tvTimeCurrent.text = formatTime(s.position)
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.LocalBinder).getService()
            musicService?.addListener(serviceListener)
            actualizarUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_player)

        root = findViewById(R.id.content_root_player)
        root.applySystemBarInsets()

        optionsHelper = SongOptionsHelper(this)

        tvTitle = findViewById(R.id.tvPlayerSongTitle)
        tvArtist = findViewById(R.id.tvPlayerArtist)
        tvTimeCurrent = findViewById(R.id.tvTimeCurrent)
        tvTimeTotal = findViewById(R.id.tvTimeTotal)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnRepeat = findViewById(R.id.btnRepeat)
        ivAlbumArt = findViewById(R.id.ivAlbumArt)
        seekBar = findViewById(R.id.seekBarSong)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnLoopA = findViewById(R.id.btnLoopA)
        btnLoopB = findViewById(R.id.btnLoopB)
        btnClearAB = findViewById(R.id.btnClearAB)

        // Controles
        btnPlayPause.setOnClickListener { musicService?.togglePlayPause() }
        findViewById<ImageView>(R.id.btnNext).setOnClickListener { musicService?.next() }
        findViewById<ImageView>(R.id.btnPrevious).setOnClickListener { musicService?.previous() }
        btnRepeat.setOnClickListener {
            musicService?.let { it.setLoop(!it.loopEnabled) }
        }

        // Velocidad y bucle A-B
        btnSpeed.setOnClickListener { musicService?.let { mostrarVelocidades(it) } }
        btnLoopA.setOnClickListener { marcarA() }
        btnLoopB.setOnClickListener { marcarB() }
        btnClearAB.setOnClickListener { musicService?.clearAB() }

        // Tres puntos: nombre, imagen, temporizador, bucle
        findViewById<ImageView>(R.id.btnOptions).setOnClickListener { anchor ->
            musicService?.let { service ->
                optionsHelper.showPlayerMenu(anchor, service) {
                    lastCoverPath = null
                    actualizarUI()
                }
            }
        }

        // Barra de progreso
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvTimeCurrent.text = formatTime(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                musicService?.seekTo(sb?.progress ?: 0)
            }
        })

        // Volumen del sistema
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val seekVolume = findViewById<SeekBar>(R.id.seekBarVolume)
        seekVolume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        seekVolume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Menú lateral (bloqueado: choca con el gesto de cerrar)
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayoutPlayer)
        val navView = findViewById<NavigationView>(R.id.nav_view_player)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_inicio -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                R.id.nav_descargas -> {
                    startActivity(Intent(this, DownloadActivity::class.java))
                    finish()
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Deslizar a la derecha para cerrar
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 != null) {
                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y
                    if (abs(diffX) > abs(diffY) && abs(diffX) > SWIPE_THRESHOLD &&
                        abs(velocityX) > SWIPE_VELOCITY_THRESHOLD && diffX > 0
                    ) {
                        finish()
                        return true
                    }
                }
                return false
            }
        })
    }

    // El estado vive en el servicio: al volver a la pantalla se vuelve a enlazar y se redibuja todo.
    override fun onStart() {
        super.onStart()
        serviceBound = bindService(
            Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        uiHandler.post(progressRunnable)
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

    // ---------- Velocidad ----------
    private fun mostrarVelocidades(service: MusicService) {
        val labels = speeds.map { formatSpeed(it) }.toTypedArray()
        val actual = speeds.indexOfFirst { it == service.speed }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Velocidad de reproducción")
            .setSingleChoiceItems(labels, actual) { dialog, which ->
                service.setSpeed(speeds[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Bucle A-B ----------
    private fun marcarA() {
        val s = musicService ?: return
        if (s.currentSong == null) return
        s.setLoopA()
        Snackbar.make(root, "Punto A marcado en ${formatTime(s.loopA)}", Snackbar.LENGTH_SHORT).show()
    }

    private fun marcarB() {
        val s = musicService ?: return
        if (s.currentSong == null) return
        when {
            s.loopA < 0 ->
                Snackbar.make(root, "Primero marca el punto A", Snackbar.LENGTH_SHORT).show()
            !s.setLoopB() ->
                Snackbar.make(root, "El punto B debe ir después del A", Snackbar.LENGTH_SHORT).show()
            else ->
                Snackbar.make(
                    root,
                    "Repitiendo de ${formatTime(s.loopA)} a ${formatTime(s.loopB)}",
                    Snackbar.LENGTH_LONG
                )
                    .setAction("QUITAR") { s.clearAB() }
                    .setActionTextColor(ContextCompat.getColor(this, R.color.bg_mint))
                    .show()
        }
    }

    private fun estilo(tv: TextView, activo: Boolean) {
        val oscuro = ContextCompat.getColor(this, R.color.text_dark)
        if (activo) {
            tv.setBackgroundColor(oscuro)
            tv.setTextColor(ContextCompat.getColor(this, R.color.white))
        } else {
            tv.setBackgroundColor(Color.parseColor("#D3F5E7"))
            tv.setTextColor(oscuro)
        }
    }

    // ---------- Pantalla ----------
    private fun actualizarUI() {
        val service = musicService ?: return
        val song = service.currentSong

        if (song == null) {
            // Si ya había música y se detuvo (temporizador), cerramos la pantalla
            if (hadSong) {
                finish()
            } else {
                tvTitle.text = "Sin reproducción"
                tvArtist.text = ""
            }
            return
        }
        hadSong = true

        tvTitle.text = SongStore.displayTitle(this, song)
        tvArtist.text = song.artist
        btnPlayPause.setImageResource(
            if (service.isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        btnRepeat.alpha = if (service.loopEnabled) 1f else 0.4f
        seekBar.max = service.duration
        tvTimeTotal.text = formatTime(service.duration)

        // Velocidad y bucle A-B
        btnSpeed.text = formatSpeed(service.speed)
        estilo(btnSpeed, service.speed != 1f)
        btnLoopA.text = if (service.loopA >= 0) "A ${formatTime(service.loopA)}" else "A"
        btnLoopB.text = if (service.loopB >= 0) "B ${formatTime(service.loopB)}" else "B"
        estilo(btnLoopA, service.loopA >= 0)
        estilo(btnLoopB, service.loopB >= 0)
        btnClearAB.visibility =
            if (service.loopA >= 0 || service.loopB >= 0) View.VISIBLE else View.GONE

        if (song.path != lastCoverPath) {
            lastCoverPath = song.path
            val bmp = SongStore.loadCover(this, song)
            if (bmp != null) {
                ivAlbumArt.imageTintList = null
                ivAlbumArt.scaleType = ImageView.ScaleType.CENTER_CROP
                ivAlbumArt.setImageBitmap(bmp)
            } else {
                ivAlbumArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
                ivAlbumArt.setImageResource(android.R.drawable.ic_menu_gallery)
                ivAlbumArt.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_dark))
            }
        }
    }

    private fun formatTime(ms: Int): String =
        String.format(Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60)

    private fun formatSpeed(s: Float): String =
        (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "x"

    // ---------- Gestos ----------
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Si el toque empieza sobre una barra, no se interpreta como "deslizar para cerrar"
            ignoreSwipe = touchesView(seekBar, ev) ||
                    touchesView(findViewById(R.id.seekBarVolume), ev)
        }
        if (!ignoreSwipe) gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun touchesView(v: View, ev: MotionEvent): Boolean {
        val r = Rect()
        if (!v.getGlobalVisibleRect(r)) return false
        val pad = (16 * resources.displayMetrics.density).toInt()
        r.inset(-pad, -pad)
        return r.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }
}