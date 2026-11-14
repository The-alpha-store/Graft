package dev.graft.app.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the device currently has *a* network with internet capability. Deliberately lenient: it
 * only returns false when there's clearly nothing to talk to (no active network at all), never
 * because a captive-portal or "not yet validated" flag is set - wrongly refusing to try is worse than
 * trying and failing, which is handled separately.
 */
object NetworkStatus {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
