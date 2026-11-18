package com.example.cuicatl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private val formato: String?, // null = se muestra la extensión del archivo
    private val host: LibraryHost
) : ListAdapter<Song, SongAdapter.VH>(Diff) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvSongArtist)
        val tvFormat: TextView = view.findViewById(R.id.tvSongFormat)
        val btnFavorite: ImageView = view.findViewById(R.id.btnFavorite)
        val btnOptions: View = view.findViewById(R.id.btnSongOptions)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = getItem(position)
        val ctx = holder.itemView.context

        holder.tvTitle.text = SongStore.displayTitle(ctx, song)
        holder.tvArtist.text = SongStore.displayArtist(ctx, song)
        holder.tvFormat.text =
            formato ?: song.path.substringAfterLast('.', "").uppercase().take(4)

        fun pintarCorazon(esFavorita: Boolean) {
            holder.btnFavorite.setImageResource(
                if (esFavorita) R.drawable.baseline_favorite_24
                else R.drawable.baseline_favorite_border_24
            )
        }
        pintarCorazon(FavoritesManager.esFavorita(ctx, song))

        holder.btnFavorite.setOnClickListener { pintarCorazon(host.onFavoriteClick(song)) }

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) host.onSongClick(currentList, pos)
        }

        holder.btnOptions.setOnClickListener { host.onSongOptions(it, song) }
    }

    private object Diff : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(old: Song, new: Song) = old.path == new.path

        // El título y el artista que se muestran salen de SongStore (renombrados),
        // no de Song, así que siempre se vuelven a pintar.
        override fun areContentsTheSame(old: Song, new: Song) = false
    }
}