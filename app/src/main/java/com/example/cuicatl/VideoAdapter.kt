package com.example.cuicatl

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private object VideoDiff : DiffUtil.ItemCallback<VideoItem>() {
    override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.id == b.id
    override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
}

/** Lista de videos con miniatura (se carga en segundo plano) y duración. */
class VideoAdapter(private val host: VideoHost) :
    ListAdapter<VideoItem, VideoAdapter.VH>(VideoDiff) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivVideoThumb)
        val title: TextView = view.findViewById(R.id.tvVideoTitle)
        val duration: TextView = view.findViewById(R.id.tvVideoDuration)
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val cache = LruCache<Long, Bitmap>(80)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val video = getItem(position)
        holder.title.text = video.title
        holder.duration.text = DateUtils.formatElapsedTime(video.durationMs / 1000)
        holder.itemView.setOnClickListener { host.onVideoClick(video) }
        holder.itemView.setOnLongClickListener { host.onVideoLongClick(video); true }

        holder.thumb.tag = video.id
        val guardada = cache.get(video.id)
        if (guardada != null) {
            holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.thumb.setImageBitmap(guardada)
            return
        }
        holder.thumb.scaleType = ImageView.ScaleType.CENTER
        holder.thumb.setImageResource(android.R.drawable.ic_media_play)

        val contexto = holder.itemView.context.applicationContext
        try {
            executor.execute {
                val bmp = miniatura(contexto, video.uri) ?: return@execute
                cache.put(video.id, bmp)
                main.post {
                    if (holder.thumb.tag == video.id) {
                        holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                        holder.thumb.setImageBitmap(bmp)
                    }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "Adapter cerrado", e)
        }
    }

    /** Se llama al destruir la vista del fragment. */
    fun cerrar() {
        executor.shutdownNow()
    }

    private fun miniatura(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(
                1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 112
            )
        } catch (e: Exception) {
            Log.w(TAG, "Sin miniatura", e)
            null
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val TAG = "CuicatlVideo"
    }
}