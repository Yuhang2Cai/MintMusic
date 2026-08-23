package com.example.timedmusicplayer.lyrics

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.timedmusicplayer.R

class LyricPageView(context: Context) : FrameLayout(context) {
    private val title = TextView(context)
    private val hint = TextView(context)
    private val list = RecyclerView(context)
    private val adapter = LinesAdapter()
    private var lines: List<LyricLine> = emptyList()
    private var current = -1
    private val pageDots = LinearLayout(context)
    var onPageSelected: ((Boolean) -> Unit)? = null

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        title.apply {
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
        }
        hint.apply {
            text = context.getString(R.string.lyrics_swipe_back)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
            setPadding(0, dp(4), 0, dp(12))
        }
        list.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@LyricPageView.adapter
            overScrollMode = OVER_SCROLL_NEVER
        }
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pageDots.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = GONE
            addView(dot(true))
            addView(dot(false), LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(13) })
            getChildAt(0).setOnClickListener { onPageSelected?.invoke(false) }
            getChildAt(1).setOnClickListener { onPageSelected?.invoke(true) }
        }
        column.addView(pageDots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun setPageIndicatorVisible(hasLyrics: Boolean, lyricsSelected: Boolean) {
        pageDots.visibility = if (hasLyrics) VISIBLE else GONE
        pageDots.getChildAt(0).setBackgroundResource(
            if (lyricsSelected) R.drawable.bg_page_dot_inactive else R.drawable.bg_page_dot_active
        )
        pageDots.getChildAt(1).setBackgroundResource(
            if (lyricsSelected) R.drawable.bg_page_dot_active else R.drawable.bg_page_dot_inactive
        )
        pageDots.getChildAt(0).layoutParams.width = dp(if (lyricsSelected) 7 else 18)
        pageDots.getChildAt(1).layoutParams.width = dp(if (lyricsSelected) 18 else 7)
        pageDots.getChildAt(0).requestLayout()
        pageDots.getChildAt(1).requestLayout()
    }

    private fun dot(active: Boolean) = View(context).apply {
        background = ContextCompat.getDrawable(context, if (active) R.drawable.bg_page_dot_active else R.drawable.bg_page_dot_inactive)
        layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
    }

    fun setLyrics(trackTitle: String, value: List<LyricLine>) {
        title.text = trackTitle
        lines = value
        current = -1
        adapter.submit(value, current)
    }

    fun updatePosition(positionMs: Long) {
        val next = LrcParser.currentIndex(lines, positionMs)
        if (next == current) return
        current = next
        adapter.submit(lines, current)
        if (next >= 0) list.smoothScrollToPosition(next)
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private inner class LinesAdapter : RecyclerView.Adapter<LineHolder>() {
        private var items: List<LyricLine> = emptyList()
        private var selected = -1
        fun submit(value: List<LyricLine>, current: Int) {
            items = value
            selected = current
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineHolder {
            val text = TextView(parent.context).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(13), dp(10), dp(13))
            }
            return LineHolder(text)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: LineHolder, position: Int) {
            val active = position == selected
            holder.text.text = items[position].text
            holder.text.textSize = if (active) 21f else 17f
            holder.text.setTypeface(holder.text.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            holder.text.setTextColor(ContextCompat.getColor(context,
                if (active) R.color.app_sage_dark else R.color.app_text_secondary))
        }
    }
    private class LineHolder(val text: TextView) : RecyclerView.ViewHolder(text)
}
