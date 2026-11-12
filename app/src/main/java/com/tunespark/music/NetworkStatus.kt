package com.tunespark.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Tiny helper used by the offline-first library and download flows. */
object NetworkStatus {

    /** True when the device currently has a usable internet connection. */
    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            // If connectivity cannot be queried, assume online so streaming keeps
            // working exactly as before.
            true
        }
    }
}