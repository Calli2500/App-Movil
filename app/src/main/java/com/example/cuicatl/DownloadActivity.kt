package com.example.cuicatl

import android.content.ClipboardManager
import androidx.core.net.toUri
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

class DownloadActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var etUrl: EditText
    private lateinit var container: LinearLayout

    private class DlCard(
        val title: TextView,
        val percent: TextView,
        val bar: ProgressBar,
        val status: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_download)

        findViewById<View>(R.id.content_root_download).applySystemBarInsets()

        etUrl = findViewById(R.id.etUrl)
        container = findViewById(R.id.containerDownloads)

        // Menú lateral
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayoutDownload)
        val navView = findViewById<NavigationView>(R.id.nav_view_download)
        findViewById<ImageView>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_inicio -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                R.id.nav_favoritos -> {
                    startActivity(Intent(this, FavoritesActivity::class.java))
                    finish()
                }
                R.id.nav_reproductor -> {
                    startActivity(Intent(this, PlayerActivity::class.java))
                    finish()
                }
                R.id.nav_acerca -> startActivity(Intent(this, AboutActivity::class.java))
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        findViewById<Button>(R.id.btnSearchFree).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        findViewById<Button>(R.id.btnExtractAudio).setOnClickListener {
            startActivity(Intent(this, ExtractAudioActivity::class.java))
        }

        findViewById<ImageView>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "El portapapeles está vacío", Toast.LENGTH_SHORT).show()
            } else {
                etUrl.setText(text.trim())
                etUrl.setSelection(etUrl.text.length)
            }
        }

        findViewById<Button>(R.id.btnDownloadMp3).setOnClickListener { iniciarDescarga() }
    }

    private fun iniciarDescarga() {
        var url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Pega primero un enlace", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
        }

        val view = LayoutInflater.from(this).inflate(R.layout.item_download, container, false)
        val card = DlCard(
            view.findViewById(R.id.tvDlTitle),
            view.findViewById(R.id.tvDlPercent),
            view.findViewById(R.id.pbDl),
            view.findViewById(R.id.tvDlStatus)
        )
        card.title.text = url.toUri().host ?: url
        container.addView(view, 0)
        etUrl.setText("")

        descargar(url, card)
    }

    private fun descargar(url: String, card: DlCard) {
        Thread {
            try {
                Downloader.download(
                    context = this,
                    url = url,
                    onStart = { name ->
                        ui.post {
                            card.title.text = name
                            card.status.text = getString(R.string.dl_downloading)
                        }
                    },
                    onProgress = { done, total ->
                        ui.post {
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                card.bar.progress = p
                                card.percent.text = getString(R.string.percent_format, p)
                                card.status.text = getString(
                                    R.string.dl_progress_of, formatSize(done), formatSize(total)
                                )
                            } else {
                                card.bar.isIndeterminate = true
                                card.status.text = formatSize(done)
                            }
                        }
                    }
                )
                ui.post {
                    card.bar.isIndeterminate = false
                    card.bar.progress = 100
                    card.percent.text = getString(R.string.dl_done)
                    card.status.text = getString(R.string.dl_saved)
                }
            } catch (e: Exception) {
                ui.post {
                    card.bar.isIndeterminate = false
                    card.percent.text = getString(R.string.dl_error)
                    card.status.text = e.message ?: getString(R.string.dl_failed)
                }
            }
        }.start()
    }
}