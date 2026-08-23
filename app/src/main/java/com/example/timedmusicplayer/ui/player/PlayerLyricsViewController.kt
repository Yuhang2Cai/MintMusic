package com.example.timedmusicplayer.ui.player

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.databinding.ActivityPlayerEditorialBinding
import com.example.timedmusicplayer.lyrics.LyricLine
import com.example.timedmusicplayer.lyrics.LyricPageView
import kotlin.math.abs

/** Owns lyric-page gestures, transitions and page-indicator rendering. */
class PlayerLyricsViewController(
    private val activity: AppCompatActivity,
    private val binding: ActivityPlayerEditorialBinding,
    private val onPageSelected: (Boolean) -> Unit
) {
    private val lyricPage = LyricPageView(activity).apply { visibility = View.GONE }
    private var displayedTrackId: String? = null
    private var displayedLines: List<LyricLine> = emptyList()
    private var displayedLyricsPage = false
    private var touchDownX = 0f
    private var touchDownY = 0f

    init {
        activity.addContentView(
            lyricPage,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(64) }
        )
        binding.dotCover.setOnClickListener { onPageSelected(false) }
        binding.dotLyrics.setOnClickListener { onPageSelected(true) }
        lyricPage.onPageSelected = onPageSelected
    }

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (abs(dx) >= SWIPE_THRESHOLD_PX && abs(dx) > abs(dy)) {
                    onPageSelected(dx < 0)
                }
            }
        }
    }

    fun render(state: PlayerUiState) {
        if (displayedTrackId != state.lyricTrackId || displayedLines != state.lyrics) {
            displayedTrackId = state.lyricTrackId
            displayedLines = state.lyrics
            lyricPage.setLyrics(state.title, state.lyrics)
        }
        lyricPage.updatePosition(state.positionMs)
        renderPage(state.isLyricsPageVisible, state.lyrics.isNotEmpty())
    }

    private fun renderPage(lyricsSelected: Boolean, hasLyrics: Boolean) {
        if (!hasLyrics && lyricPage.visibility == View.VISIBLE) {
            lyricPage.animate().cancel()
            lyricPage.visibility = View.GONE
            lyricPage.translationX = 0f
            displayedLyricsPage = false
        } else if (lyricsSelected != displayedLyricsPage) {
            displayedLyricsPage = lyricsSelected
            lyricPage.animate().cancel()
            if (lyricsSelected) {
                lyricPage.translationX = -binding.root.width.toFloat()
                lyricPage.visibility = View.VISIBLE
                lyricPage.animate().translationX(0f).setDuration(PAGE_ANIMATION_MS).start()
            } else if (lyricPage.visibility == View.VISIBLE) {
                lyricPage.animate().translationX(-binding.root.width.toFloat())
                    .setDuration(PAGE_ANIMATION_MS)
                    .withEndAction {
                        lyricPage.visibility = View.GONE
                        lyricPage.translationX = 0f
                    }
                    .start()
            }
        }
        renderIndicator(hasLyrics, lyricsSelected)
    }

    private fun renderIndicator(hasLyrics: Boolean, lyricsSelected: Boolean) {
        binding.coverPageIndicator.visibility = if (hasLyrics) View.VISIBLE else View.GONE
        binding.dotCover.setBackgroundResource(
            if (lyricsSelected) R.drawable.bg_page_dot_inactive else R.drawable.bg_page_dot_active
        )
        binding.dotLyrics.setBackgroundResource(
            if (lyricsSelected) R.drawable.bg_page_dot_active else R.drawable.bg_page_dot_inactive
        )
        binding.dotCover.layoutParams.width = dp(if (lyricsSelected) 7 else 18)
        binding.dotLyrics.layoutParams.width = dp(if (lyricsSelected) 18 else 7)
        binding.dotCover.requestLayout()
        binding.dotLyrics.requestLayout()
        lyricPage.setPageIndicatorVisible(hasLyrics, lyricsSelected)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val SWIPE_THRESHOLD_PX = 120f
        const val PAGE_ANIMATION_MS = 220L
    }
}
