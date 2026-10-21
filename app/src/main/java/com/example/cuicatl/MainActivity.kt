package com.example.cuicatl

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 1. Corregimos el error del ID cambiando "main" por "drawer_layout"
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.drawer_layout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 2. Enlazamos los componentes del menú lateral
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val btnMenu = findViewById<ImageView>(R.id.btnMenu)

        // 3. Programamos que al tocar la hamburguesa, se abra el panel lateral
        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // 4. Programamos qué hace cada opción del menú
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_inicio -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
                R.id.nav_descargas -> {
                    startActivity(Intent(this, DownloadActivity::class.java))
                    // Quitamos finish() aquí para que Main sea la base a la que regresas
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
                R.id.nav_reproductor -> {
                    startActivity(Intent(this, PlayerActivity::class.java))
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            }
            true
        }
    }
}