package com.example.cuicatl

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView

/** Pestaña de videos MP4. Reutiliza fragment_song_list (lista + texto de "vacío"). */
class VideoListFragment : Fragment(R.layout.fragment_song_list) {

    private val vm: LibraryViewModel by lazy {
        ViewModelProvider(requireActivity())[LibraryViewModel::class.java]
    }
    private var adapter: VideoAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rvSongs)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)

        val videoAdapter = VideoAdapter(requireActivity() as VideoHost)
        adapter = videoAdapter
        rv.adapter = videoAdapter
        rv.itemAnimator = null

        vm.videos.observe(viewLifecycleOwner) { videos ->
            videoAdapter.submitList(videos)
            tvEmpty.isVisible = videos.isEmpty()
            tvEmpty.setText(
                if (vm.consulta.isEmpty()) R.string.lib_sin_videos else R.string.lib_sin_resultados
            )
        }
    }

    override fun onDestroyView() {
        adapter?.cerrar()
        adapter = null
        super.onDestroyView()
    }
}