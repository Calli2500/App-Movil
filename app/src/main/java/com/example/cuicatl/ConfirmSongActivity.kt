package com.example.cuicatl

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

class ConfirmSongActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_ORIGINAL = "original"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_NEW_TITLE = "new_title"

        fun intentFor(context: Context, song: Song, newTitle: String, image: Uri?): Intent {
            val i = Intent(context, ConfirmSongActivity::class.java)
                .putExtra(EXTRA_PATH, song.path)
                .putExtra(EXTRA_ORIGINAL, song.originalTitle)
                .putExtra(EXTRA_ARTIST, song.artist)
                .putExtra(EXTRA_NEW_TITLE, newTitle)
            if (image != null) {
                // La imagen viaja como data y se le da permiso de lectura a la otra pantalla
                i.data = image
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return i
        }
    }

    private lateinit var song: Song
    private lateinit var newTitle: String
    private var image: Uri? = null
    private lateinit var btnAccept: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_confirm_song)
        findViewById<View>(R.id.content_root_confirm).applySystemBarInsets()

        // getExtra: leemos lo que mandó el formulario
        val path = intent.getStringExtra(EXTRA_PATH)
        val title = intent.getStringExtra(EXTRA_NEW_TITLE)
        if (path == null || title == null) {
            finish()
            return
        }
        song = Song(
            path,
            intent.getStringExtra(EXTRA_ORIGINAL) ?: "",
            intent.getStringExtra(EXTRA_ARTIST) ?: ""
        )
        newTitle = title
        image = intent.data

        findViewById<TextView>(R.id.tvConfirmTitle).text = newTitle
        findViewById<TextView>(R.id.tvConfirmBefore).text =
            "Antes: ${SongStore.displayTitle(this, song)}"
        mostrarPortada()

        btnAccept = findViewById(R.id.btnAccept)
        btnAccept.setOnClickListener { aceptar() }

        // Editar: regresa al formulario, que conserva lo escrito
        findViewById<Button>(R.id.btnEdit).setOnClickListener { finish() }
    }

    private fun aceptar() {
        btnAccept.isEnabled = false
        val uri = image
        // Guardar la imagen puede tardar un poco: se hace fuera del hilo principal
        Thread {
            SongStore.setCustomTitle(this, song, newTitle)
            val ok = if (uri != null) SongStore.saveImage(this, song, uri) else true
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) {
                    startActivity(ThankYouActivity.intentFor(this, newTitle))
                    setResult(RESULT_OK) // avisa al formulario que ya puede cerrarse
                    finish()
                } else {
                    btnAccept.isEnabled = true
                    Snackbar.make(
                        findViewById(R.id.content_root_confirm),
                        "No se pudo guardar la imagen", Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun mostrarPortada() {
        val iv = findViewById<ImageView>(R.id.ivConfirmCover)
        val bmp = image?.let { decodeScaled(it) } ?: SongStore.loadCover(this, song)
        if (bmp != null) {
            iv.imageTintList = null
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(bmp)
        } else {
            iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
            iv.setImageResource(android.R.drawable.ic_menu_gallery)
            iv.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_dark))
        }
    }
}