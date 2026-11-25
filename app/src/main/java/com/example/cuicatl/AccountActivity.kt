package com.example.cuicatl

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Cuenta opcional (Lab 10): registro e inicio de sesión con Firebase Authentication
 * y perfil guardado en Cloud Firestore (colección "Customer", un documento por usuario).
 * Incluye restablecer contraseña por correo y edición del perfil.
 */
class AccountActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var modoRegistro = false

    /** Perfil leído de Firestore (sirve para rellenar el diálogo de edición). */
    private var perfil: Map<String, String> = emptyMap()

    private lateinit var root: View
    private lateinit var tvSubtitulo: TextView
    private lateinit var grupoForm: LinearLayout
    private lateinit var grupoPerfil: LinearLayout
    private lateinit var grupoSesion: LinearLayout
    private lateinit var progreso: ProgressBar
    private lateinit var btnAccion: Button
    private lateinit var btnAlternar: Button
    private lateinit var btnOlvide: Button

    private lateinit var etNombre: TextInputEditText
    private lateinit var etCiudad: TextInputEditText
    private lateinit var etPais: TextInputEditText
    private lateinit var etTelefono: TextInputEditText
    private lateinit var etCorreo: TextInputEditText
    private lateinit var etClave: TextInputEditText

    private lateinit var tvHola: TextView
    private lateinit var tvCorreoSesion: TextView
    private lateinit var tvDetalle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.aplicar(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        root = findViewById(R.id.content_root_account)
        root.applySystemBarInsets()

        tvSubtitulo = findViewById(R.id.tvAccountSubtitle)
        grupoForm = findViewById(R.id.grupoForm)
        grupoPerfil = findViewById(R.id.grupoPerfil)
        grupoSesion = findViewById(R.id.grupoSesion)
        progreso = findViewById(R.id.progresoCuenta)
        btnAccion = findViewById(R.id.btnAccion)
        btnAlternar = findViewById(R.id.btnAlternar)
        btnOlvide = findViewById(R.id.btnOlvide)

        etNombre = findViewById(R.id.etNombre)
        etCiudad = findViewById(R.id.etCiudad)
        etPais = findViewById(R.id.etPais)
        etTelefono = findViewById(R.id.etTelefono)
        etCorreo = findViewById(R.id.etCorreo)
        etClave = findViewById(R.id.etClave)

        tvHola = findViewById(R.id.tvHola)
        tvCorreoSesion = findViewById(R.id.tvCorreoSesion)
        tvDetalle = findViewById(R.id.tvDetalle)

        btnAccion.setOnClickListener { if (modoRegistro) registrar() else iniciarSesion() }
        btnAlternar.setOnClickListener {
            modoRegistro = !modoRegistro
            mostrarFormulario()
        }
        btnOlvide.setOnClickListener { restablecerClave() }
        findViewById<Button>(R.id.btnEditarPerfil).setOnClickListener { editarPerfil() }
        findViewById<Button>(R.id.btnCerrarSesion).setOnClickListener {
            auth.signOut()
            perfil = emptyMap()
            etClave.setText("")
            modoRegistro = false
            mostrarFormulario()
        }
        findViewById<Button>(R.id.btnVolverCuenta).setOnClickListener { finish() }

        // La sesión de Firebase persiste sola entre aperturas de la app
        if (auth.currentUser != null) mostrarSesion() else mostrarFormulario()
    }

    // ---------- Vistas ----------

    private fun mostrarFormulario() {
        grupoSesion.visibility = View.GONE
        grupoForm.visibility = View.VISIBLE
        grupoPerfil.visibility = if (modoRegistro) View.VISIBLE else View.GONE
        btnOlvide.visibility = if (modoRegistro) View.GONE else View.VISIBLE
        tvSubtitulo.setText(if (modoRegistro) R.string.acct_subtitle_register else R.string.acct_subtitle_login)
        btnAccion.setText(if (modoRegistro) R.string.acct_btn_register else R.string.acct_btn_login)
        btnAlternar.setText(if (modoRegistro) R.string.acct_switch_to_login else R.string.acct_switch_to_register)
        cargando(false)
    }

    private fun mostrarSesion() {
        val user = auth.currentUser ?: return mostrarFormulario()
        grupoForm.visibility = View.GONE
        grupoSesion.visibility = View.VISIBLE
        tvSubtitulo.setText(R.string.acct_subtitle_session)
        tvHola.text = getString(R.string.acct_hello, user.email ?: "")
        tvCorreoSesion.text = user.email ?: ""
        tvDetalle.text = ""
        perfil = emptyMap()

        // Lectura del perfil guardado en Firestore (documento con el UID del usuario)
        db.collection(COLECCION).document(user.uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val nombre = doc.getString("Name").orEmpty()
                    val ciudad = doc.getString("City").orEmpty()
                    val pais = doc.getString("Country").orEmpty()
                    val telefono = doc.getString("Phone Number").orEmpty()
                    perfil = mapOf(
                        "Name" to nombre, "City" to ciudad,
                        "Country" to pais, "Phone Number" to telefono
                    )
                    if (nombre.isNotBlank()) tvHola.text = getString(R.string.acct_hello, nombre)
                    if (nombre.isNotBlank() && user.displayName != nombre) {
                        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(nombre).build())
                    }
                    tvDetalle.text = listOf(ciudad, pais, telefono)
                        .filter { it.isNotBlank() }.joinToString("\n")
                }
            }
            .addOnFailureListener { aviso(R.string.acct_err_generic) }
    }

    private fun cargando(si: Boolean) {
        progreso.visibility = if (si) View.VISIBLE else View.GONE
        btnAccion.isEnabled = !si
        btnAlternar.isEnabled = !si
        btnOlvide.isEnabled = !si
    }

    // ---------- Acciones ----------

    private fun correoClave(): Pair<String, String>? {
        val correo = etCorreo.text?.toString().orEmpty().trim()
        val clave = etClave.text?.toString().orEmpty()
        if (!Patterns.EMAIL_ADDRESS.matcher(correo).matches()) { aviso(R.string.acct_err_email); return null }
        if (clave.length < 6) { aviso(R.string.acct_err_password); return null }
        return correo to clave
    }

    private fun iniciarSesion() {
        val (correo, clave) = correoClave() ?: return
        cargando(true)
        auth.signInWithEmailAndPassword(correo, clave)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) mostrarSesion() else { cargando(false); avisoError(task.exception) }
            }
    }

    private fun registrar() {
        val nombre = etNombre.text?.toString().orEmpty().trim()
        if (nombre.isEmpty()) { aviso(R.string.acct_err_name); return }
        val (correo, clave) = correoClave() ?: return
        cargando(true)
        auth.createUserWithEmailAndPassword(correo, clave)
            .addOnCompleteListener(this) { task ->
                val uid = auth.currentUser?.uid
                if (!task.isSuccessful || uid == null) {
                    cargando(false); avisoError(task.exception); return@addOnCompleteListener
                }
                auth.currentUser?.updateProfile(
                    UserProfileChangeRequest.Builder().setDisplayName(nombre).build()
                )
                val datos = hashMapOf(
                    "Name" to nombre,
                    "City" to etCiudad.text?.toString().orEmpty().trim(),
                    "Country" to etPais.text?.toString().orEmpty().trim(),
                    "Phone Number" to etTelefono.text?.toString().orEmpty().trim()
                )
                // La contraseña NUNCA se guarda en Firestore: solo la maneja Firebase Authentication
                db.collection(COLECCION).document(uid).set(datos)
                    .addOnSuccessListener { modoRegistro = false; mostrarSesion() }
                    .addOnFailureListener { aviso(R.string.acct_err_profile); modoRegistro = false; mostrarSesion() }
            }
    }

    /** Manda el correo de Firebase para elegir una contraseña nueva. */
    private fun restablecerClave() {
        val correo = etCorreo.text?.toString().orEmpty().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(correo).matches()) { aviso(R.string.acct_err_email); return }
        cargando(true)
        auth.sendPasswordResetEmail(correo).addOnCompleteListener(this) { task ->
            cargando(false)
            when {
                task.isSuccessful -> aviso(R.string.acct_reset_sent)
                // Mismo mensaje si el correo no existe: así no se revela qué correos tienen cuenta
                task.exception is FirebaseAuthInvalidUserException -> aviso(R.string.acct_reset_sent)
                else -> avisoError(task.exception)
            }
        }
    }

    /** Diálogo para cambiar nombre, ciudad, país y teléfono (se guardan con merge en Firestore). */
    private fun editarPerfil() {
        val user = auth.currentUser ?: return
        val vista = layoutInflater.inflate(R.layout.dialog_edit_profile, null)
        val eNombre = vista.findViewById<TextInputEditText>(R.id.etEdNombre)
        val eCiudad = vista.findViewById<TextInputEditText>(R.id.etEdCiudad)
        val ePais = vista.findViewById<TextInputEditText>(R.id.etEdPais)
        val eTelefono = vista.findViewById<TextInputEditText>(R.id.etEdTelefono)
        eNombre.setText(perfil["Name"].orEmpty())
        eCiudad.setText(perfil["City"].orEmpty())
        ePais.setText(perfil["Country"].orEmpty())
        eTelefono.setText(perfil["Phone Number"].orEmpty())

        val dialogo = AlertDialog.Builder(this)
            .setTitle(R.string.acct_edit_title)
            .setView(vista)
            .setPositiveButton(R.string.acct_btn_save, null) // se asigna abajo para no cerrar si hay error
            .setNegativeButton(R.string.acct_cancel, null)
            .create()

        dialogo.setOnShowListener {
            val btnGuardar = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            btnGuardar.setOnClickListener { boton ->
                val nombre = eNombre.text?.toString().orEmpty().trim()
                if (nombre.isEmpty()) {
                    eNombre.error = getString(R.string.acct_err_name)
                    return@setOnClickListener
                }
                val datos = hashMapOf(
                    "Name" to nombre,
                    "City" to eCiudad.text?.toString().orEmpty().trim(),
                    "Country" to ePais.text?.toString().orEmpty().trim(),
                    "Phone Number" to eTelefono.text?.toString().orEmpty().trim()
                )
                boton.isEnabled = false
                db.collection(COLECCION).document(user.uid).set(datos, SetOptions.merge())
                    .addOnSuccessListener {
                        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(nombre).build())
                        dialogo.dismiss()
                        aviso(R.string.acct_profile_saved)
                        mostrarSesion()
                    }
                    .addOnFailureListener {
                        boton.isEnabled = true
                        aviso(R.string.acct_err_save)
                    }
            }
        }
        dialogo.show()
    }

    private fun aviso(res: Int) = Snackbar.make(root, res, Snackbar.LENGTH_LONG).show()

    private fun avisoError(e: Exception?) = aviso(
        when (e) {
            is FirebaseAuthInvalidCredentialsException,
            is FirebaseAuthInvalidUserException -> R.string.acct_err_credentials
            is FirebaseAuthUserCollisionException -> R.string.acct_err_exists
            is FirebaseNetworkException -> R.string.acct_err_network
            else -> R.string.acct_err_generic
        }
    )

    companion object {
        private const val COLECCION = "Customer"
    }
}