package com.example.cuicatl

import android.net.Uri

/** Un video MP4 del teléfono (MediaStore). [title] es el nombre mostrado (puede estar renombrado). */
data class VideoItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val dateAdded: Long,
    val originalTitle: String = title
)

/** Lo que el fragment de videos le pide a la activity. */
interface VideoHost {
    fun onVideoClick(video: VideoItem)
    fun onVideoLongClick(video: VideoItem)
}