package com.example.timedmusicplayer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.media3.common.PlaybackException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.pow
import kotlin.random.Random

class NetworkMonitor(context: Context, private val onChanged: (Boolean) -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChanged(true)
        override fun onLost(network: Network) = onChanged(isOnline())
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChanged(isOnline())
    }
    fun start() = runCatching { manager.registerDefaultNetworkCallback(callback) }
    fun stop() = runCatching { manager.unregisterNetworkCallback(callback) }
    fun isOnline(): Boolean {
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

object PlaybackErrorClassifier {
    fun isRetryable(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            val message = error.cause?.message.orEmpty()
            if (listOf("401", "403", "404").any(message::contains)) return false
            return true
        }
        return error.cause is UnknownHostException || error.cause is SocketTimeoutException || error.cause is IOException ||
            error.errorCode in PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    }
}

class RecoveryPolicy(private val maxAttempts: Int = 4) {
    fun delayMs(attempt: Int, jitter: Boolean = true): Long? {
        if (attempt !in 1..maxAttempts) return null
        val base = (1_000.0 * 2.0.pow((attempt - 1).toDouble())).toLong().coerceAtMost(8_000L)
        if (!jitter) return base
        return (base * Random.nextDouble(0.8, 1.2)).toLong()
    }
}
