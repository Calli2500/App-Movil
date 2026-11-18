package com.example.cuicatl

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

class MusicService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.example.cuicatl.TOGGLE"
        const val ACTION_NEXT = "com.example.cuicatl.NEXT"
        const val ACTION_PREV = "com.example.cuicatl.PREV"
        const val ACTION_CLOSE = "com.example.cuicatl.CLOSE"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "CuicatlService"

        // Dónde se guarda lo último que sonaba (para retomarlo al abrir la app)
        private const val PREFS = "player_prefs"
        private const val KEY_QUEUE = "last_queue"
        private const val KEY_INDEX = "last_index"
        private const val KEY_POS = "last_pos"
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
        get() = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
    val position: Int
        get() = runCatching { mediaPlayer?.currentPosition ?: 0 }.getOrDefault(0)
    val duration: Int
        get() = runCatching { mediaPlayer?.duration ?: 0 }.getOrDefault(0)

    override fun onCreate() {
        super.onCreate()
        speed = getSharedPreferences("player_prefs", MODE_PRIVATE).getFloat("speed", 1f)
        AppNotifications.ensureChannels(this)
        // Pausa al desconectar los audífonos (señal del sistema, por eso no se exporta)
        ContextCompat.registerReceiver(
            this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Sesión multimedia: pantalla de bloqueo, Bluetooth, audífonos y controles del sistema
        session = MediaSessionCompat(this, "Cuicatl").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (!isPlaying) togglePlayPause() }
                override fun onPause() { if (isPlaying) togglePlayPause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { previous() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                override fun onStop() { stopMusic() }
            })
        }
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
        actualizarSesion()
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
            .setContentText(SongStore.displayArtist(this, song))
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
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2) // anterior, pausa/play, siguiente
                    .setMediaSession(session.sessionToken)
            )
            .setDeleteIntent(actionIntent(ACTION_CLOSE, 5)) // deslizar para cerrar
            .build()
    }

    private fun updateNotification() {
        val song = currentSong
        if (song == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return
        }
        // Volver a llamar a startForeground simplemente actualiza la notificación
        // El tipo de servicio existe desde Android 10 (API 29); antes no se indica
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(song), tipo)
    }

    /** Vuelve a leer título y portada (después de editar la canción). */
    fun refreshMeta() {
        coverPath = null
        updateNotification()
    }

    /** Quita una canción eliminada de la cola; si era la que sonaba, pasa a la siguiente. */
    fun removeSong(path: String) {
        val current = currentSong
        val newQueue = queue.filter { it.path != path }
        originalQueue = originalQueue.filter { it.path != path }
        if (newQueue.size == queue.size) return

        if (current?.path == path) {
            if (newQueue.isEmpty()) {
                stopMusic()
                return
            }
            queue = newQueue
            currentIndex = currentIndex.coerceAtMost(newQueue.lastIndex)
            guardarCola()
            startCurrent()
        } else {
            queue = newQueue
            currentIndex = newQueue.indexOfFirst { it.path == current?.path }.coerceAtLeast(0)
            guardarCola()
            guardarPosicion()
            notifyChanged()
        }
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
        guardarCola()
        if (sameSong) {
            guardarPosicion()
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
            if (pedirFoco()) mp.start() // si otra app tiene el audio (llamada), queda en pausa
            mediaPlayer = mp
            applySpeed()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo reproducir el archivo", e)
            mp.release()
            Toast.makeText(this, "No se pudo reproducir este archivo", Toast.LENGTH_SHORT).show()
        }
        notifyChanged()
        if (mediaPlayer != null) {
            guardarPosicion()
            programarGuardado()
        }
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
            perdioFocoTemporal = false
            abandonarFoco() // deja que otras apps retomen su audio
        } else if (pedirFoco()) {
            mp.start()
            applySpeed()
        }
        guardarPosicion()
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
        actualizarSesion() // la barra de la pantalla de bloqueo salta a la nueva posición
    }

    // ---------- Cola: saltar, quitar, mover y mezclar ----------
    /** Salta a una canción de la cola. */
    fun playIndex(index: Int) {
        if (index !in queue.indices) return
        currentIndex = index
        startCurrent()
    }

    /** Quita de la cola la canción en esa posición (si era la actual, pasa a la siguiente). */
    fun removeAt(index: Int) {
        val song = queue.getOrNull(index) ?: return
        removeSong(song.path)
    }

    /** Mueve una canción dentro de la cola (posiciones de la cola). La que suena no se interrumpe. */
    fun moveInQueue(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val lista = queue.toMutableList()
        lista.add(to, lista.removeAt(from))
        currentIndex = when {
            currentIndex == from -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        queue = lista
        if (!shuffleEnabled) originalQueue = lista
        guardarCola()
        guardarPosicion()
        notifyChanged()
    }

    /** Mezcla solo lo que viene después de la canción que suena. */
    fun shuffleUpcoming() {
        if (currentIndex !in queue.indices) return
        val lista = queue.take(currentIndex + 1) + queue.drop(currentIndex + 1).shuffled()
        queue = lista
        if (!shuffleEnabled) originalQueue = lista
        guardarCola()
        guardarPosicion()
        notifyChanged()
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
        guardarCola()
        guardarPosicion()
        notifyChanged()
    }

    // ---------- Velocidad ----------
    fun setSpeed(value: Float) {
        speed = value
        getSharedPreferences("player_prefs", MODE_PRIVATE).edit { putFloat("speed", value) }
        applySpeed()
        notifyChanged()
    }

    private fun applySpeed() {
        val mp = mediaPlayer ?: return
        try {
            if (mp.isPlaying) mp.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo aplicar la velocidad", e)
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
                    Log.d(TAG, "El reproductor se está reiniciando", e)
                }
                handler.postDelayed(this, 100)
            }
        }
    }

    private fun seekExact(mp: MediaPlayer, ms: Int) {
        mp.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
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
        val r = Runnable {
            guardarPosicion()
            stopMusic(olvidar = false) // el temporizador no borra dónde ibas
        }
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

    // ---------- Sesión multimedia (pantalla de bloqueo, Bluetooth, audífonos) ----------
    private lateinit var session: MediaSessionCompat

    private fun actualizarSesion() {
        val song = currentSong
        if (song == null) {
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
                    .build()
            )
            session.isActive = false
            return
        }
        session.isActive = true

        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, SongStore.displayTitle(this, song))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, SongStore.displayArtist(this, song))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration.toLong())
        coverBitmap?.let { meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(meta.build())

        val acciones = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(acciones)
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    position.toLong(),
                    if (isPlaying) speed else 0f
                )
                .build()
        )
    }

    // ---------- Convivir con otras apps: foco de audio y audífonos ----------
    private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }

    // true si la pausa la causó algo pasajero (llamada, notificación) y hay que reanudar solo
    private var perdioFocoTemporal = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { cambio ->
        when (cambio) {
            // Otra app tomó el audio para quedárselo (por ejemplo, un video): pausa y no reanuda sola
            AudioManager.AUDIOFOCUS_LOSS -> {
                perdioFocoTemporal = false
                pausar()
            }
            // Llamada o alarma: pausa y reanuda cuando termine
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying) {
                    perdioFocoTemporal = true
                    pausar()
                }
            }
            // Un aviso corto (navegador, mensaje): baja el volumen sin pausar
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1f, 1f)
                if (perdioFocoTemporal) {
                    perdioFocoTemporal = false
                    reanudar()
                }
            }
        }
    }

    private val focusRequest: AudioFocusRequestCompat =
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausar()
        }
    }

    private fun pedirFoco(): Boolean =
        AudioManagerCompat.requestAudioFocus(audioManager, focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonarFoco() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
    }

    private fun pausar() {
        if (!isPlaying) return
        mediaPlayer?.pause()
        guardarPosicion()
        notifyChanged()
    }

    private fun reanudar() {
        val mp = mediaPlayer ?: return
        if (isPlaying) return
        mp.start()
        applySpeed()
        notifyChanged()
    }

    // ---------- Retomar lo último que sonaba ----------
    private val saveRunnable = object : Runnable {
        override fun run() {
            guardarPosicion()
            if (currentSong != null) handler.postDelayed(this, 5000)
        }
    }

    private fun programarGuardado() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 5000)
    }

    /** Guarda la cola (solo cuando cambia). */
    private fun guardarCola() {
        if (queue.isEmpty()) return
        val arr = JSONArray()
        queue.forEach {
            arr.put(JSONObject().put("p", it.path).put("t", it.originalTitle).put("a", it.artist))
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit { putString(KEY_QUEUE, arr.toString()) }
    }

    /** Guarda la canción actual y el segundo en que va. */
    private fun guardarPosicion() {
        if (currentSong == null || mediaPlayer == null) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit {
            putInt(KEY_INDEX, currentIndex)
            putInt(KEY_POS, position)
        }
    }

    /**
     * Si no suena nada, vuelve a cargar la última canción en pausa y en su posición.
     * Devuelve true si se pudo retomar.
     */
    fun restaurarUltima(): Boolean {
        if (currentSong != null) return false
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = prefs.getString(KEY_QUEUE, null) ?: return false

        val songs = try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Song(o.getString("p"), o.getString("t"), o.getString("a"))
            }
        } catch (e: JSONException) {
            Log.w(TAG, "No se pudo leer la cola guardada", e)
            return false
        }

        val index = prefs.getInt(KEY_INDEX, 0)
        if (index !in songs.indices) return false
        val song = songs[index]
        if (!File(song.path).exists()) return false // la canción ya no está

        val mp = MediaPlayer()
        try {
            mp.setDataSource(song.path)
            mp.prepare()
            val dur = mp.duration
            val pos = prefs.getInt(KEY_POS, 0)
            mp.seekTo(if (pos in 0 until dur - 1000) pos else 0)
            mp.isLooping = loopEnabled
            mp.setOnCompletionListener { onSongCompleted() }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo retomar la última canción", e)
            mp.release()
            return false
        }

        // Servicio en primer plano, igual que al reproducir (la notificación se muestra en pausa)
        ContextCompat.startForegroundService(this, Intent(this, MusicService::class.java))
        mediaPlayer = mp
        originalQueue = songs
        queue = songs
        currentIndex = index
        programarGuardado()
        notifyChanged()
        return true
    }

    // ---------- Detener todo (quita el reproductor y la notificación) ----------
    /** @param olvidar si es true también borra lo guardado, para no "resucitarlo" al abrir la app. */
    fun stopMusic(olvidar: Boolean = true) {
        if (olvidar) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                remove(KEY_QUEUE)
                remove(KEY_INDEX)
                remove(KEY_POS)
            }
        }
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        sleepEndTime = 0L
        abandonarFoco()
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
        guardarPosicion()
        unregisterReceiver(noisyReceiver)
        session.release()
        abandonarFoco()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        listeners.clear()
        super.onDestroy()
    }
}