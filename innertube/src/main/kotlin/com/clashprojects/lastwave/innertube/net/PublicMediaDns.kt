package com.clashprojects.lastwave.innertube.net

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

class PublicMediaDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        filterPublicMediaAddresses(hostname, delegate.lookup(hostname))
}

internal fun filterPublicMediaAddresses(
    hostname: String,
    addresses: List<InetAddress>,
): List<InetAddress> {
    val host = hostname.lowercase().removeSuffix(".")
    val isGoogleCdn =
        host.endsWith(".googleusercontent.com") ||
            host.endsWith(".ggpht.com") ||
            host.endsWith(".ytimg.com")
    // VPN DNS can map official CDN hosts to private addresses; TLS still validates their certs.
    if (addresses.isEmpty() || (!isGoogleCdn && addresses.any { !it.isPublicAddress() })) {
        throw UnknownHostException("Media host does not resolve exclusively to public addresses")
    }
    return addresses
}

internal fun InetAddress.isPublicAddress(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
        return false
    }
    val b = address.map { it.toInt() and 0xff }
    return when (b.size) {
        4 -> {
            when {
                b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 -> false
                b[0] == 100 && b[1] in 64..127 -> false
                b[0] == 169 && b[1] == 254 -> false
                b[0] == 172 && b[1] in 16..31 -> false
                b[0] == 192 && b[1] == 168 -> false
                b[0] == 192 && b[1] == 0 && (b[2] == 0 || b[2] == 2) -> false
                b[0] == 198 && (b[1] in 18..19 || (b[1] == 51 && b[2] == 100)) -> false
                b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
                else -> true
            }
        }

        16 -> {
            !(
                (b[0] and 0xfe == 0xfc) ||
                    (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
            )
        }

        else -> false
    }
}
