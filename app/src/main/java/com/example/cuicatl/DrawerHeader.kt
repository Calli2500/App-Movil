package com.example.cuicatl

import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth

/** Cabecera del menú lateral: separa el logo de la barra de estado y muestra el usuario con sesión. */
object DrawerHeader {

    fun enlazar(drawer: DrawerLayout, nav: NavigationView) {
        actualizar(nav)
        separarDeBarraDeEstado(nav)
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerStateChanged(newState: Int) {
                if (newState != DrawerLayout.STATE_IDLE) actualizar(nav)
            }
        })
    }

    /** Agrega a la cabecera un relleno superior igual al alto de la barra de estado. */
    private fun separarDeBarraDeEstado(nav: NavigationView) {
        val header = nav.getHeaderView(0) ?: return
        nav.post {
            val top = ViewCompat.getRootWindowInsets(nav)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            header.setPadding(header.paddingLeft, top, header.paddingRight, header.paddingBottom)
        }
    }

    fun actualizar(nav: NavigationView) {
        val tv = nav.getHeaderView(0)?.findViewById<TextView>(R.id.tvNavUsuario) ?: return
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            tv.visibility = View.GONE
            return
        }
        tv.text = user.displayName?.takeIf { it.isNotBlank() } ?: user.email.orEmpty()
        tv.visibility = View.VISIBLE
    }
}