package com.example.cuicatl

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

class SearchActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: View
    private lateinit var etQuery: EditText
    private lateinit var container: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnMore: Button

    private val pageSize = 20
    private var currentQuery = ""
    private var offset = 0
    private var requestId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_search)

        root = findViewById(R.id.content_root_search)
        root.applySystemBarInsets()

        etQuery = findViewById(R.id.etQuery)
        container = findViewById(R.id.containerResults)
        progress = findViewById(R.id.progressSearch)
        tvStatus = findViewById(R.id.tvSearchStatus)
        btnMore = findViewById(R.id.btnLoadMore)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnDoSearch).setOnClickListener { buscar() }
        btnMore.setOnClickListener { cargarPagina() }
        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                buscar()
                true
            } else false
        }

        buscar() // al abrir: lo más popular de la semana
    }

    private fun buscar() {
        currentQuery = etQuery.text.toString().trim()
        offset = 0
        container.removeAllViews()
        tvStatus.text = ""
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etQuery.windowToken, 0)
        cargarPagina()
    }

    private fun cargarPagina() {
        val miId = ++requestId
        val q = currentQuery
        val off = offset
        progress.visibility = View.VISIBLE
        btnMore.visibility = View.GONE

        Thread {
            try {
                val tracks = Jamendo.search(q, off, pageSize)
                ui.post { if (miId == requestId && !isDestroyed) mostrar(tracks) }
            } catch (e: Exception) {
                ui.post { if (miId == requestId && !isDestroyed) mostrarError(e) }
            }
        }.start()
    }

    private fun mostrar(tracks: List<JamendoTrack>) {
        progress.visibility = View.GONE
        if (offset == 0) {
            tvStatus.text = when {
                tracks.isEmpty() -> "Sin resultados para «$currentQuery»"
                currentQuery.isEmpty() -> "Populares de la semana"
                else -> "Resultados para «$currentQuery»"
            }
        }
        tracks.forEach { agregarVista(it) }
        offset += tracks.size
        btnMore.visibility = if (tracks.size == pageSize) View.VISIBLE else View.GONE
    }

    private fun mostrarError(e: Exception) {
        progress.visibility = View.GONE
        if (offset == 0) tvStatus.text = "No se pudo cargar"
        Snackbar.make(root, e.message ?: "Error de conexión", Snackbar.LENGTH_LONG)
            .setAction("REINTENTAR") { cargarPagina() }
            .setActionTextColor(ContextCompat.getColor(this, R.color.bg_mint))
            .show()
    }

    private fun agregarVista(t: JamendoTrack) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_track, container, false)
        v.findViewById<TextView>(R.id.tvTrackTitle).text = t.name
        v.findViewById<TextView>(R.id.tvTrackArtist).text =
            if (t.album.isBlank()) t.artist else "${t.artist} · ${t.album}"
        v.findViewById<TextView>(R.id.tvTrackDuration).text = formatDuration(t.durationSec)
        ImageLoader.load(t.imageUrl, v.findViewById<ImageView>(R.id.ivTrackCover))

        // Intent explícito + putExtra (ver TrackDetailActivity.intentFor)
        v.setOnClickListener { startActivity(TrackDetailActivity.intentFor(this, t)) }
        container.addView(v)
    }
}