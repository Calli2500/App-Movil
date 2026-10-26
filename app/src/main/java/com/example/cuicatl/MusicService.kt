package com.example.cuicatl

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class MusicService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.example.cuicatl.TOGGLE"
        const val ACTION_NEXT = "com.example.cuicatl.NEXT"
        const val ACTION_PREV = "com.example.cuicatl.PREV"
        const val ACTION_CLOSE = "com.example.cuicatl.CLOSE"
        private const val NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private val listeners = mutableSetOf<() -> Unit>()

    // Portada para la notificación (se guarda para no cargarla en cada cambio)
    private var coverPath: String? = null
    private var coverBitmap: Bitmap? = null

    var queue: List<Song> = emptyList()
        private set
    var currentIndex: Int = -1
        private set
    var loopEnabled: Boolean = false
        private set
    var sleepEndTime: Long = 0L
        private set

    var speed: Float = 1f
        private set

    var loopA: Int = -1
        private set
    var loopB: Int = -1
        private set
    val abActive: Boolean get() = loopA >= 0 && loopB > loopA

    val currentSong: Song? get() = queue.getOrNull(currentIndex)

    val isPlaying: Boolean
        get() = try { mediaPlayer?.isPlaying == true } catch (e: IllegalStateException) { false }
    val position: Int
        get() = try { mediaPlayer?.currentPosition ?: 0 } catch (e: IllegalStateException) { 0 }
    val duration: Int
        get() = try { mediaPlayer?.duration ?: 0 } catch (e: IllegalStateException) { 0 }

    override fun onCreate() {
        super.onCreate()
        speed = getSharedPreferences("player_prefs", Context.MODE_PRIVATE).getFloat("speed", 1f)
        AppNotifications.ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Aquí llegan los toques a los botones de la notificación
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
            ACTION_CLOSE -> stopMusic()
        }
        return START_NOT_STICKY
    }

    // ---------- Listeners para que las pantallas se actualicen ----------
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
        updateNotification()
    }

    // ---------- Notificación ----------
    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(song: Song): Notification {
        // Al tocar la notificación se abre el reproductor
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (song.path != coverPath) {
            coverPath = song.path
            coverBitmap = SongStore.loadCover(this, song)
        }

        val playPauseIcon =
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (isPlaying) "Pausar" else "Reproducir"

        return NotificationCompat.Builder(this, AppNotifications.CHANNEL_PLAYER)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(SongStore.displayTitle(this, song))
            .setContentText(song.artist)
            .setLargeIcon(coverBitmap)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", actionIntent(ACTION_PREV, 1))
            .addAction(playPauseIcon, playPauseText, actionIntent(ACTION_TOGGLE, 2))
            .addAction(android.R.drawable.ic_media_next, "Siguiente", actionIntent(ACTION_NEXT, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar", actionIntent(ACTION_CLOSE, 4))
            .build()
    }

    private fun updateNotification() {
        val song = currentSong
        if (song == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return
        }
        // Volver a llamar a startForeground simplemente actualiza la notificación
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(song),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    /** Vuelve a leer título y portada (después de editar la canción). */
    fun refreshMeta() {
        coverPath = null
        updateNotification()
    }

    // ---------- Reproducción ----------
    fun playQueue(songs: List<Song>, index: Int) {
        if (index !in songs.indices) return
        // Servicio en primer plano: sigue vivo aunque se cierre la app
        ContextCompat.startForegroundService(this, Intent(this, MusicService::class.java))

        val sameSong = currentSong?.path == songs[index].path && mediaPlayer != null
        originalQueue = songs
        if (shuffleEnabled) {
            val chosen = songs[index]
            queue = listOf(chosen) + (songs - chosen).shuffled()
            currentIndex = 0
        } else {
            queue = songs
            currentIndex = index
        }
        if (sameSong) {
            notifyChanged()
        } else {
            startCurrent()
        }
    }

    private fun startCurrent() {
        val song = currentSong ?: return
        mediaPlayer?.release()
        mediaPlayer = null
        resetAB()

        val mp = MediaPlayer()
        try {
            mp.setDataSource(song.path)
            mp.prepare()
            mp.isLooping = loopEnabled
            mp.setOnCompletionListener { onSongCompleted() }
            mp.start()
            mediaPlayer = mp
            applySpeed()
        } catch (e: Exception) {
            e.printStackTrace()
            mp.release()
            Toast.makeText(this, "No se pudo reproducir este archivo", Toast.LENGTH_SHORT).show()
        }
        notifyChanged()
    }

    private fun onSongCompleted() {
        if (currentIndex < queue.lastIndex) {
            currentIndex++
            startCurrent()
        } else {
            mediaPlayer?.seekTo(0)
            notifyChanged()
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
        } else {
            mp.start()
            applySpeed()
        }
        notifyChanged()
    }

    fun next() {
        if (queue.isEmpty()) return
        currentIndex = (currentIndex + 1) % queue.size
        startCurrent()
    }

    fun previous() {
        if (queue.isEmpty()) return
        if (position > 3000) {
            mediaPlayer?.seekTo(0)
            notifyChanged()
            return
        }
        currentIndex = if (currentIndex > 0) currentIndex - 1 else queue.lastIndex
        startCurrent()
    }

    fun seekTo(ms: Int) {
        mediaPlayer?.seekTo(ms)
    }

    // ---------- Aleatorio ----------
    fun setShuffle(enabled: Boolean) {
        if (enabled == shuffleEnabled) return
        shuffleEnabled = enabled
        val current = currentSong
        if (current != null) {
            if (enabled) {
                // La canción actual queda primera y el resto se mezcla
                queue = listOf(current) + (originalQueue - current).shuffled()
                currentIndex = 0
            } else {
                val idx = originalQueue.indexOf(current)
                if (idx >= 0) {
                    queue = originalQueue
                    currentIndex = idx
                }
            }
        }
        notifyChanged()
    }

    // ---------- Velocidad ----------
    fun setSpeed(value: Float) {
        speed = value
        getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("speed", value).apply()
        applySpeed()
        notifyChanged()
    }

    private fun applySpeed() {
        val mp = mediaPlayer ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            if (mp.isPlaying) mp.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------- Bucle A-B ----------
    private val abRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && abActive) {
                try {
                    if (mp.isPlaying && mp.currentPosition >= loopB) seekExact(mp, loopA)
                } catch (e: IllegalStateException) {
                    // el reproductor se está reiniciando
                }
                handler.postDelayed(this, 100)
            }
        }
    }

    private fun seekExact(mp: MediaPlayer, ms: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mp.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
        } else {
            mp.seekTo(ms)
        }
    }

    private fun updateAbWatcher() {
        handler.removeCallbacks(abRunnable)
        if (abActive) handler.post(abRunnable)
    }

    private fun resetAB() {
        handler.removeCallbacks(abRunnable)
        loopA = -1
        loopB = -1
    }

    fun setLoopA() {
        if (mediaPlayer == null) return
        loopA = position
        if (loopB <= loopA) loopB = -1
        updateAbWatcher()
        notifyChanged()
    }

    fun setLoopB(): Boolean {
        if (mediaPlayer == null || loopA < 0) return false
        val p = position
        if (p <= loopA + 500) return false
        loopB = p
        updateAbWatcher()
        notifyChanged()
        return true
    }

    fun clearAB() {
        resetAB()
        notifyChanged()
    }

    var shuffleEnabled: Boolean = false
        private set

    // Orden original de la lista, para poder volver a él al apagar el aleatorio
    private var originalQueue: List<Song> = emptyList()

    // ---------- Bucle de canción ----------
    fun setLoop(enabled: Boolean) {
        loopEnabled = enabled
        mediaPlayer?.isLooping = enabled
        notifyChanged()
    }

    // ---------- Temporizador de apagado ----------
    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        val delay = minutes * 60_000L
        sleepEndTime = System.currentTimeMillis() + delay
        val r = Runnable { stopMusic() }
        sleepRunnable = r
        handler.postDelayed(r, delay)
        notifyChanged()
    }

    fun cancelSleepTimer() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        sleepEndTime = 0L
        notifyChanged()
    }

    // ---------- Detener todo (quita el reproductor y la notificación) ----------
    fun stopMusic() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        sleepEndTime = 0L
        resetAB()
        mediaPlayer?.release()
        mediaPlayer = null
        queue = emptyList()
        originalQueue = emptyList()
        currentIndex = -1
        coverPath = null
        coverBitmap = null
        notifyChanged() // con la cola vacía, esto retira la notificación
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        listeners.clear()
        super.onDestroy()
    }
}