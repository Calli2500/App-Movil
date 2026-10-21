package com.example.cuicatl

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

class DownloadActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)

        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayoutDownload)
        val navView = findViewById<NavigationView>(R.id.nav_view_download)
        val btnMenu = findViewById<ImageView>(R.id.btnMenu)

        // Abrir el menú al presionar la hamburguesa
        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Configurar navegación
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_inicio -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish() // Cierra Descargas para no acumular memoria
                }
                R.id.nav_reproductor -> {
                    startActivity(Intent(this, PlayerActivity::class.java))
                    finish() // Cierra Descargas y va al Reproductor
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }
}