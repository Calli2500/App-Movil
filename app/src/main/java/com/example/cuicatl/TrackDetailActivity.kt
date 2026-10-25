package com.example.cuicatl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

class TrackDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "track_name"
        const val EXTRA_ARTIST = "track_artist"
        const val EXTRA_ALBUM = "track_album"
        const val EXTRA_DURATION = "track_duration"
        const val EXTRA_IMAGE = "track_image"
        const val EXTRA_DOWNLOAD = "track_download"
        const val EXTRA_ALLOWED = "track_allowed"
        const val EXTRA_LICENSE = "track_license"

        const val EXTRA_ID = "track_id"

        /** Arma el Intent con los datos de la canción (putExtra). */
        fun intentFor(context: Context, t: JamendoTrack): Intent =
            Intent(context, TrackDetailActivity::class.java).apply {
                putExtra(EXTRA_NAME, t.name)
                putExtra(EXTRA_ARTIST, t.artist)
                putExtra(EXTRA_ALBUM, t.album)
                putExtra(EXTRA_DURATION, t.durationSec)
                putExtra(EXTRA_IMAGE, t.imageUrl)
                putExtra(EXTRA_DOWNLOAD, t.downloadUrl)
                putExtra(EXTRA_ALLOWED, t.downloadAllowed)
                putExtra(EXTRA_LICENSE, t.licenseUrl)
                putExtra(EXTRA_ID, t.id)
            }
    }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var downloading = false

    private lateinit var root: View
    private lateinit var btnDownload: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private var name = ""

    private var trackId = ""
    private var artist = ""
    private var imageUrl = ""
    private var downloadUrl = ""
    private var allowed = false
    private var licenseUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_track_detail)

        root = findViewById(R.id.content_root_detail)
        root.applySystemBarInsets()

        // Recibimos los datos con getExtra
        name = intent.getStringExtra(EXTRA_NAME) ?: run { finish(); return }
        trackId = intent.getStringExtra(EXTRA_ID) ?: ""
        artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
        val album = intent.getStringExtra(EXTRA_ALBUM) ?: ""
        val duration = intent.getIntExtra(EXTRA_DURATION, 0)
        imageUrl = intent.getStringExtra(EXTRA_IMAGE) ?: ""
        downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD) ?: ""
        allowed = intent.getBooleanExtra(EXTRA_ALLOWED, false)
        licenseUrl = intent.getStringExtra(EXTRA_LICENSE) ?: ""

        btnDownload = findViewById(R.id.btnDownloadTrack)
        progressBar = findViewById(R.id.pbDetail)
        tvStatus = findViewById(R.id.tvDetailStatus)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvDetailTitle).text = name
        findViewById<TextView>(R.id.tvDetailArtist).text = artist
        findViewById<TextView>(R.id.tvDetailMeta).text =
            listOf(album, formatDuration(duration)).filter { it.isNotBlank() }.joinToString(" · ")
        ImageLoader.load(imageUrl, findViewById<ImageView>(R.id.ivDetailCover))

        val tvLicense = findViewById<TextView>(R.id.tvDetailLicense)
        tvLicense.text = "Licencia: ${licenciaCorta()}"
        tvLicense.paintFlags = tvLicense.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tvLicense.setOnClickListener { abrirLicencia() }

        if (!allowed || downloadUrl.isBlank()) {
            btnDownload.isEnabled = false
            tvStatus.text = "El artista no permite descargar esta canción desde apps."
        }
        btnDownload.setOnClickListener { confirmarDescarga() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ciclo de vida: si el usuario sale de la pantalla, se cancela la descarga en curso
        if (isFinishing) cancelled = true
    }

    private fun confirmarDescarga() {
        AlertDialog.Builder(this)
            .setTitle("Descargar canción")
            .setMessage("«$name» de $artist\n\nLicencia: ${licenciaCorta()}\n\n¿Guardarla en tu biblioteca?")
            .setPositiveButton("Descargar") { _, _ -> iniciarDescarga() }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Ver licencia") { _, _ -> abrirLicencia() }
            .show()
    }

    private fun iniciarDescarga() {
        if (downloading) return
        downloading = true
        btnDownload.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        tvStatus.text = "Conectando…"

        Thread {
            try {
                val path = Downloader.download(
                    context = this@TrackDetailActivity,
                    url = Jamendo.fileUrl(trackId),
                    fileName = "$artist - $name.mp3",
                    isCancelled = { cancelled },
                    onProgress = { done, total ->
                        ui.post {
                            if (total > 0) {
                                progressBar.isIndeterminate = false
                                progressBar.progress = (done * 100 / total).toInt()
                            }
                            tvStatus.text = formatSize(done) +
                                    if (total > 0) " de ${formatSize(total)}" else ""
                        }
                    }
                )

                // Nombre original y portada de Jamendo para la biblioteca
                val song = Song(path, name, artist)
                SongStore.setCustomTitle(this@TrackDetailActivity, song, name)
                ImageLoader.loadBlocking(imageUrl)?.let {
                    SongStore.saveBitmap(this@TrackDetailActivity, song, it)
                }

                ui.post { terminado() }
            } catch (e: Downloader.CancelledException) {
                // La pantalla se cerró: no hay nada que mostrar
            } catch (e: Exception) {
                ui.post { if (!isDestroyed) fallo(e) }
            }
        }.start()
    }

    private fun terminado() {
        if (isDestroyed) return
        downloading = false
        progressBar.visibility = View.GONE
        tvStatus.text = "Guardada en Música/Cuicatl"
        btnDownload.isEnabled = true

        Snackbar.make(root, "Descargada: $name", Snackbar.LENGTH_LONG)
            .setAction("VER BIBLIOTECA") {
                val i = Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                startActivity(i)
            }
            .setActionTextColor(ContextCompat.getColor(this, R.color.bg_mint))
            .show()
    }

    private fun fallo(e: Exception) {
        downloading = false
        progressBar.visibility = View.GONE
        btnDownload.isEnabled = true
        tvStatus.text = ""
        AlertDialog.Builder(this)
            .setTitle("No se pudo descargar")
            .setMessage(e.message ?: "Error desconocido")
            .setPositiveButton("Reintentar") { _, _ -> iniciarDescarga() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** "http://creativecommons.org/licenses/by-nc-nd/3.0/" -> "CC BY-NC-ND 3.0" */
    private fun licenciaCorta(): String {
        val m = Regex("licenses/([a-z\\-]+)/([0-9.]+)").find(licenseUrl)
            ?: return "Creative Commons"
        return "CC ${m.groupValues[1].uppercase()} ${m.groupValues[2]}"
    }

    private fun abrirLicencia() {
        if (licenseUrl.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(licenseUrl)))
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(root, "No hay un navegador para abrir el enlace", Snackbar.LENGTH_SHORT).show()
        }
    }
}