package com.example.cuicatl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class ThankYouActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TITLE = "title"

        fun intentFor(context: Context, title: String): Intent =
            Intent(context, ThankYouActivity::class.java).putExtra(EXTRA_TITLE, title)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_thank_you)
        findViewById<View>(R.id.content_root_thanks).applySystemBarInsets()

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        findViewById<TextView>(R.id.tvThanksMessage).text =
            "\"$title\" se guardó correctamente."

        findViewById<Button>(R.id.btnBackToLibrary).setOnClickListener { volver() }

        // El botón "atrás" también lleva a la biblioteca (reemplazo moderno de onBackPressed)
        onBackPressedDispatcher.addCallback(this) { volver() }
    }

    private fun volver() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}