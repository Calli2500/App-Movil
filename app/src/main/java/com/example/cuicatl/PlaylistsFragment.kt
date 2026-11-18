package com.example.cuicatl

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView

/** Tercera pestaña de la biblioteca: lista de playlists y botón para crear una. */
class PlaylistsFragment : Fragment(R.layout.fragment_playlists) {

    private lateinit var adapter: PlaylistAdapter
    private lateinit var tvEmpty: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvEmpty = view.findViewById(R.id.tvPlaylistsEmpty)
        val rv = view.findViewById<RecyclerView>(R.id.rvPlaylists)

        adapter = PlaylistAdapter { p ->
            startActivity(PlaylistDetailActivity.intentFor(requireContext(), p.id))
        }
        rv.adapter = adapter
        rv.itemAnimator = null

        view.findViewById<View>(R.id.btnNewPlaylist).setOnClickListener {
            PlaylistDialogs.pedirNombre(requireActivity(), "Nueva playlist", "") { nombre ->
                PlaylistStore.create(requireContext(), nombre)
                refrescar()
            }
        }
    }

    // Al volver del detalle (renombrar, eliminar, quitar canciones) la lista se actualiza
    override fun onResume() {
        super.onResume()
        refrescar()
    }

    private fun refrescar() {
        val listas = PlaylistStore.all(requireContext())
        adapter.submitList(listas)
        tvEmpty.isVisible = listas.isEmpty()
    }
}