package com.example.cuicatl

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView

class SongListFragment : Fragment(R.layout.fragment_song_list) {

    companion object {
        const val FORMATO_MP3 = "MP3"
        const val FORMATO_OTRO = "OTRO"
        private const val ARG_FORMATO = "formato"

        fun nuevo(formato: String) = SongListFragment().apply {
            arguments = Bundle().apply { putString(ARG_FORMATO, formato) }
        }
    }

    // ViewModel compartido con MainActivity (mismo dueño: la activity)
    private val vm: LibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[LibraryViewModel::class.java]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val formato = requireArguments().getString(ARG_FORMATO) ?: FORMATO_MP3
        val host = requireActivity() as LibraryHost

        val rv = view.findViewById<RecyclerView>(R.id.rvSongs)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)

        val adapter = SongAdapter(formato, host)
        rv.adapter = adapter
        rv.itemAnimator = null

        val datos = vm.mp3
        datos.observe(viewLifecycleOwner) { songs ->
            adapter.submitList(songs)
            tvEmpty.isVisible = songs.isEmpty()
            tvEmpty.setText(
                if (vm.consulta.isEmpty()) R.string.lib_sin_archivos else R.string.lib_sin_resultados
            )
        }
    }
}