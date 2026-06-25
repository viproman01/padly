package app.padly.android.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
        val certFingerprint: String?,
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
                        val host = svc.host?.hostAddress ?: return
                        val attrs = svc.attributes ?: emptyMap()
                        val serverId = attrs["id"]?.toString(Charsets.UTF_8)
                        val fp = attrs["fp"]?.toString(Charsets.UTF_8)
                        trySend(Found(svc.serviceName, host, svc.port, serverId, fp))
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }
        nsd.discoverServices("_padly._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { nsd.stopServiceDiscovery(listener) }
    }
}
