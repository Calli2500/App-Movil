package com.example.cuicatl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class PlaylistAdapter(
    private val onClick: (Playlist) -> Unit
) : ListAdapter<Playlist, PlaylistAdapter.VH>(Diff) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvPlaylistItemName)
        val tvCount: TextView = view.findViewById(R.id.tvPlaylistItemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = getItem(position)
        val n = p.paths.size
        holder.tvName.text = p.name
        holder.tvCount.text =
            holder.itemView.resources.getQuantityString(R.plurals.playlist_songs, n, n)
        holder.itemView.setOnClickListener { onClick(p) }
    }

    private object Diff : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(old: Playlist, new: Playlist) = old.id == new.id
        override fun areContentsTheSame(old: Playlist, new: Playlist) = old == new
    }
}