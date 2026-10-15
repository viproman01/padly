package app.padly.android.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * mDNS discovery of Padly Mac instances on the local network.
 * Listens for `_padly._tcp` services; emits each resolved instance.
 */
class Discovery(private val context: Context) {
    private val nsd: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    data class Found(
        val name: String,
        val host: String,
        val port: Int,
        val serverId: String?,
    )

    fun search(): Flow<Found> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo?, errorCode: Int) {}
                    override fun onServiceResolved(svc: NsdServiceInfo) {
                        val host = urlHost(svc) ?: return
                        val attrs = svc.attributes ?: emptyMap()
                        val serverId = attrs["id"]?.toString(Charsets.UTF_8)
                        val friendly = attrs["name"]?.toString(Charsets.UTF_8) ?: svc.serviceName
                        trySend(Found(friendly, host, svc.port, serverId))
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }
        nsd.discoverServices("_padly._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { nsd.stopServiceDiscovery(listener) }
    }

    /**
     * Prefer the Mac's IPv4 address: NSD often resolves a link-local IPv6 one
     * first, which is unusable in a ws:// URL. IPv6 fallback is bracketed.
     */
    private fun urlHost(svc: NsdServiceInfo): String? {
        val candidates: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) {
            svc.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(svc.host)
        }
        val v4 = candidates.firstOrNull { it is Inet4Address }
        if (v4 != null) return v4.hostAddress
        val v6 = candidates.firstOrNull()?.hostAddress?.substringBefore('%') ?: return null
        return "[$v6]"
    }
}
