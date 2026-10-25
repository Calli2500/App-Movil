package com.example.cuicatl

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

class ExtractAudioActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var working = false
    private var videoUri: Uri? = null

    private lateinit var root: View
    private lateinit var group: View
    private lateinit var tvVideoName: TextView
    private lateinit var tvVideoMeta: TextView
    private lateinit var etOutputName: EditText
    private lateinit var btnExtract: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private var musicService: MusicService? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    // Selector de video del sistema (no necesita permisos)
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) videoElegido(uri)
    }

    // Permiso de notificaciones (Android 13+). Se extrae el audio aunque se rechace.
    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { comenzarExtraccion() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_extract_audio)

        root = findViewById(R.id.content_root_extract)
        root.applySystemBarInsets()

        group = findViewById(R.id.groupVideo)
        tvVideoName = findViewById(R.id.tvVideoName)
        tvVideoMeta = findViewById(R.id.tvVideoMeta)
        etOutputName = findViewById(R.id.etOutputName)
        btnExtract = findViewById(R.id.btnExtract)
        progressBar = findViewById(R.id.pbExtract)
        tvStatus = findViewById(R.id.tvExtractStatus)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnPickVideo).setOnClickListener {
            if (!working) pickVideo.launch("video/*")
        }
        btnExtract.setOnClickListener { pedirPermisoYExtraer() }

        // Canal de notificaciones listo desde el principio
        AppNotifications.ensureChannels(this)
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(
            Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        musicService = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) cancelled = true
    }

    // ---------- Selección del video ----------
    private fun videoElegido(uri: Uri) {
        videoUri = uri
        val nombre = nombreDe(uri)

        var duracionMs = 0L
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(this, uri)
                duracionMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                r.release()
            }
        } catch (e: Exception) {
            // sin duración, se muestra igual
        }

        tvVideoName.text = nombre
        tvVideoMeta.text = if (duracionMs > 0) {
            "Duración: ${formatDuration((duracionMs / 1000).toInt())}"
        } else "Video seleccionado"
        etOutputName.setText(nombre.substringBeforeLast('.', nombre))
        group.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        tvStatus.text = ""
    }

    private fun nombreDe(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) {
                val n = it.getString(0)
                if (!n.isNullOrBlank()) return n
            }
        }
        return uri.lastPathSegment ?: "video"
    }

    // ---------- Extracción ----------
    private fun pedirPermisoYExtraer() {
        if (working || videoUri == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            comenzarExtraccion()
        }
    }

    private fun comenzarExtraccion() {
        val uri = videoUri ?: return
        if (working) return
        val nombre = etOutputName.text.toString().trim().ifBlank { "audio" }

        working = true
        btnExtract.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "Extrayendo audio…"

        Thread {
            try {
                val path = AudioExtractor.extract(
                    context = this@ExtractAudioActivity,
                    source = uri,
                    name = nombre,
                    isCancelled = { cancelled },
                    onProgress = { pct ->
                        ui.post {
                            progressBar.progress = pct
                            tvStatus.text = "Extrayendo audio… $pct%"
                        }
                    }
                )

                // Nombre y portada (un cuadro del video) para la biblioteca
                val song = Song(path, nombre, "Cuicatl")
                SongStore.setCustomTitle(this@ExtractAudioActivity, song, nombre)
                AudioExtractor.videoFrame(this@ExtractAudioActivity, uri)?.let {
                    SongStore.saveBitmap(this@ExtractAudioActivity, song, it)
                }

                ui.post { terminado(song) }
            } catch (e: AudioExtractor.CancelledException) {
                // la pantalla se cerró: no hay nada que mostrar
            } catch (e: Exception) {
                ui.post { if (!isDestroyed) fallo(e) }
            }
        }.start()
    }

    private fun terminado(song: Song) {
        working = false
        if (isDestroyed) {
            AppNotifications.notifyExtractionDone(applicationContext, song.originalTitle)
            return
        }
        btnExtract.isEnabled = true
        progressBar.visibility = View.GONE
        tvStatus.text = "Guardado en Música/Cuicatl"

        AppNotifications.notifyExtractionDone(this, song.originalTitle)

        Snackbar.make(root, "Audio extraído: ${song.originalTitle}", Snackbar.LENGTH_LONG)
            .setAction("REPRODUCIR") { reproducir(song) }
            .setActionTextColor(ContextCompat.getColor(this, R.color.bg_mint))
            .show()
    }

    private fun fallo(e: Exception) {
        working = false
        btnExtract.isEnabled = true
        progressBar.visibility = View.GONE
        tvStatus.text = ""
        AlertDialog.Builder(this)
            .setTitle("No se pudo extraer el audio")
            .setMessage(e.message ?: "Error desconocido")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun reproducir(song: Song) {
        val service = musicService
        if (service == null) {
            Snackbar.make(root, "Reproductor no listo, intenta de nuevo", Snackbar.LENGTH_SHORT).show()
            return
        }
        service.playQueue(listOf(song), 0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
