package com.example.cuicatl

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Reproductor de video a pantalla completa con Media3 (ExoPlayer + PlayerView). */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        private const val KEY_POS = "pos"
        private const val KEY_PLAY = "play"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var posicion = 0L
    private var reproduciendo = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.playerView)
        val barraSuperior = findViewById<View>(R.id.barraVideo)
        findViewById<TextView>(R.id.tvVideoTitulo).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        findViewById<ImageView>(R.id.btnVideoCerrar).setOnClickListener { finish() }

        posicion = savedInstanceState?.getLong(KEY_POS) ?: 0L
        reproduciendo = savedInstanceState?.getBoolean(KEY_PLAY, true) ?: true

        // Pantalla completa: las barras del sistema se ocultan y reaparecen al deslizar desde el borde
        WindowInsetsControllerCompat(window, playerView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // La barra con el título se muestra y se oculta junto con los controles
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibilidad ->
                barraSuperior.visibility = if (visibilidad == View.VISIBLE) View.VISIBLE else View.GONE
            }
        )
    }

    override fun onStart() {
        super.onStart()
        val uri = intent.getStringExtra(EXTRA_URI)?.toUri()
        if (uri == null) {
            finish()
            return
        }
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true // maneja el foco de audio: pausa la música mientras suena el video
            )
            .build()
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.seekTo(posicion)
        exo.playWhenReady = reproduciendo
        exo.prepare()
        playerView.player = exo
        player = exo
    }

    override fun onStop() {
        guardarEstado()
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    private fun guardarEstado() {
        player?.let {
            posicion = it.currentPosition
            reproduciendo = it.playWhenReady
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        guardarEstado()
        outState.putLong(KEY_POS, posicion)
        outState.putBoolean(KEY_PLAY, reproduciendo)
    }
}