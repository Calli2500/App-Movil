package com.example.cuicatl

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class EditSongActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_ORIGINAL = "original"
        private const val EXTRA_ARTIST = "artist"
        private const val STATE_IMAGE = "image"

        fun intentFor(context: Context, song: Song): Intent =
            Intent(context, EditSongActivity::class.java)
                .putExtra(EXTRA_PATH, song.path)
                .putExtra(EXTRA_ORIGINAL, song.originalTitle)
                .putExtra(EXTRA_ARTIST, song.artist)
    }

    private lateinit var song: Song
    private lateinit var etTitle: EditText
    private lateinit var ivCover: ImageView
    private var pickedImage: Uri? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                pickedImage = uri
                mostrarPortada()
            }
        }

    // Si la pantalla de confirmación dice "Aceptar" (RESULT_OK), este formulario ya cumplió y se cierra.
    // Si dice "Editar" (RESULT_CANCELED), el formulario sigue aquí con todo lo que escribiste.
    private val confirmar =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_edit_song)
        findViewById<android.view.View>(R.id.content_root_edit).applySystemBarInsets()

        // getExtra: recibimos la canción que viene del reproductor
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        song = Song(
            path,
            intent.getStringExtra(EXTRA_ORIGINAL) ?: "",
            intent.getStringExtra(EXTRA_ARTIST) ?: ""
        )

        etTitle = findViewById(R.id.etTitle)
        ivCover = findViewById(R.id.ivEditCover)
        findViewById<TextView>(R.id.tvOriginalTitle).text = "Título original: ${song.originalTitle}"

        if (savedInstanceState == null) {
            etTitle.setText(SongStore.displayTitle(this, song))
        }
        pickedImage = savedInstanceState?.getString(STATE_IMAGE)?.let { Uri.parse(it) }
        mostrarPortada()

        findViewById<Button>(R.id.btnPickImage).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.btnContinue).setOnClickListener { continuar() }

        // Reemplazo moderno de onBackPressed()
        onBackPressedDispatcher.addCallback(this) {
            if (hayCambios()) confirmarSalida() else finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pickedImage?.let { outState.putString(STATE_IMAGE, it.toString()) }
    }

    private fun hayCambios(): Boolean =
        etTitle.text.toString().trim() != SongStore.displayTitle(this, song) || pickedImage != null

    private fun confirmarSalida() {
        AlertDialog.Builder(this)
            .setTitle("¿Descartar cambios?")
            .setMessage("Si sales ahora, se perderá lo que escribiste.")
            .setPositiveButton("Descartar") { _, _ -> finish() }
            .setNegativeButton("Seguir editando", null)
            .show()
    }

    private fun continuar() {
        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) {
            etTitle.error = "Escribe un título"
            return
        }
        // putExtra: mandamos lo capturado a la pantalla de confirmación
        confirmar.launch(ConfirmSongActivity.intentFor(this, song, title, pickedImage))
    }

    private fun mostrarPortada() {
        val bmp = pickedImage?.let { decodeScaled(it) } ?: SongStore.loadCover(this, song)
        if (bmp != null) {
            ivCover.imageTintList = null
            ivCover.scaleType = ImageView.ScaleType.CENTER_CROP
            ivCover.setImageBitmap(bmp)
        } else {
            ivCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
            ivCover.setImageResource(android.R.drawable.ic_menu_gallery)
            ivCover.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_dark))
        }
    }
}