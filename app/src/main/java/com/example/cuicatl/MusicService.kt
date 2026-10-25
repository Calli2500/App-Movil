package com.example.cuicatl

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

class MusicService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private val listeners = mutableSetOf<() -> Unit>()

    var queue: List<Song> = emptyList()
        private set
    var currentIndex: Int = -1
        private set
    var loopEnabled: Boolean = false
        private set
    var sleepEndTime: Long = 0L
        private set

    // Velocidad de reproducción (se guarda en preferencias)
    var speed: Float = 1f
        private set

    // Bucle A-B en milisegundos (-1 = sin marcar)
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
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    // ---------- Listeners para que las pantallas se actualicen ----------
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { listeners.toList().forEach { it() } }

    // ---------- Reproducción ----------
    fun playQueue(songs: List<Song>, index: Int) {
        if (index !in songs.indices) return
        // Mantiene el servicio vivo aunque se cierren las pantallas
        startService(Intent(this, MusicService::class.java))

        val sameSong = currentSong?.path == songs[index].path && mediaPlayer != null
        queue = songs
        currentIndex = index
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
        resetAB() // los puntos A-B pertenecen a una canción concreta

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

    // ---------- Velocidad ----------
    fun setSpeed(value: Float) {
        speed = value
        getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("speed", value).apply()
        applySpeed()
        notifyChanged()
    }

    /**
     * Solo se aplica con la música sonando: en algunas versiones de Android,
     * cambiar la velocidad en pausa arranca la reproducción sin querer.
     */
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

    /** Marca el punto A en la posición actual. */
    fun setLoopA() {
        if (mediaPlayer == null) return
        loopA = position
        if (loopB <= loopA) loopB = -1
        updateAbWatcher()
        notifyChanged()
    }

    /** Marca el punto B en la posición actual. Devuelve false si no es válido. */
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

    // ---------- Detener todo (quita el reproductor) ----------
    fun stopMusic() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        sleepEndTime = 0L
        resetAB()
        mediaPlayer?.release()
        mediaPlayer = null
        queue = emptyList()
        currentIndex = -1
        notifyChanged()
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