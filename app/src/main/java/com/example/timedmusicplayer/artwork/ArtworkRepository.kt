package com.example.timedmusicplayer.artwork

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.ImageView
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArtworkRepository(private val context: Context) {
    private val imageLoader = ImageLoader.Builder(context).respectCacheHeaders(false).build()
    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)

    fun load(target: ImageView, track: Track?, sizePx: Int) {
        target.tag = track?.id
        if (track == null) { target.setImageResource(R.drawable.cover_placeholder); return }
        val online = track.coverUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (online != null) {
            imageLoader.enqueue(ImageRequest.Builder(context).data(online).target(target).size(sizePx)
                .placeholder(R.drawable.cover_placeholder).error(R.drawable.cover_placeholder)
                .crossfade(false).memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).build())
            return
        }
        if (track.isStream) { target.setImageResource(R.drawable.cover_placeholder); return }
        target.setImageResource(R.drawable.cover_placeholder)
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try { retriever.setDataSource(context, Uri.parse(track.uri)); retriever.embeddedPicture } catch (_: Exception) { null }
                finally { retriever.release() }
            }
            if (target.tag == track.id && bytes != null) {
                imageLoader.enqueue(ImageRequest.Builder(context).data(bytes).target(target).size(sizePx)
                    .memoryCacheKey("${track.id}:${track.sizeBytes}:${track.modifiedAtMs}:$sizePx")
                    .diskCacheKey("${track.id}:${track.sizeBytes}:${track.modifiedAtMs}:$sizePx")
                    .crossfade(false).build())
            }
        }
    }
}
