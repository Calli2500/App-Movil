package com.example.cuicatl

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import android.util.Log

class AboutActivity : AppCompatActivity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_about)

        findViewById<View>(R.id.content_root_about).applySystemBarInsets()
        findViewById<ImageView>(R.id.btnBackAbout).setOnClickListener { finish() }

        web = findViewById(R.id.webAbout)
        web.setBackgroundColor(0x00000000)
        web.settings.apply {
            javaScriptEnabled = false   // la página es estática: no se necesita JavaScript
            allowFileAccess = false     // los archivos de assets se cargan igual
            allowContentAccess = false
        }

        // Los enlaces externos se abren en el navegador, no dentro de la app
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: ActivityNotFoundException) {
                        Log.w("CuicatlAbout", "No hay navegador instalado", e)
                    }
                    return true
                }
                return false
            }
        }

        web.loadUrl("file:///android_asset/about.html")

        // Atrás: primero retrocede en el WebView y, si no hay historial, cierra la pantalla
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}