package com.example.cuicatl

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

class PlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayoutPlayer)
        val navView = findViewById<NavigationView>(R.id.nav_view_player)
        val btnClose = findViewById<ImageView>(R.id.btnClosePlayer)

        // Usamos la X (ClosePlayer) para abrir el menú o cerrar la actividad (tú decides la función).
        // Para que se comporte igual que el menú:
        btnClose.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Configurar navegación
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_inicio -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish() // Cierra Reproductor
                }
                R.id.nav_descargas -> {
                    startActivity(Intent(this, DownloadActivity::class.java))
                    finish() // Cierra Reproductor y va a Descargas
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }
}