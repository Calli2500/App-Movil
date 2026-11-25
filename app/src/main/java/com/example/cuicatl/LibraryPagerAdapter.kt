package com.example.cuicatl

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter

/** Conecta las pestañas del TabLayout con sus fragments: MP3, Videos y Playlists. */
class LibraryPagerAdapter(fragmentManager: FragmentManager, lifecycle: Lifecycle) :
    FragmentStateAdapter(fragmentManager, lifecycle) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> SongListFragment.nuevo(SongListFragment.FORMATO_MP3)
        1 -> VideoListFragment()
        else -> PlaylistsFragment()
    }
}