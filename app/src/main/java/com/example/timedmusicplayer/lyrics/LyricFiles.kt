package com.example.timedmusicplayer.lyrics

import android.content.Context
import java.io.File
import java.security.MessageDigest

object LyricFiles {
    private fun key(trackId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(trackId.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun file(context: Context, trackId: String): File = File(
        File(context.filesDir, "lyrics").apply(File::mkdirs),
        "${key(trackId)}.lrc"
    )

    fun read(context: Context, trackId: String): List<LyricLine> = file(context, trackId)
        .takeIf(File::isFile)
        ?.readText(Charsets.UTF_8)
        ?.let(LrcParser::parse)
        .orEmpty()
}

