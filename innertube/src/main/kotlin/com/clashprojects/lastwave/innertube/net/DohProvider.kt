package com.clashprojects.lastwave.innertube.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException

sealed interface DohProvider {
    data object System : DohProvider

    data object Cloudflare : DohProvider {
        const val URL = "https://cloudflare-dns.com/dns-query"
        val bootstrapHosts = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
    }

    data object Google : DohProvider {
        const val URL = "https://dns.google/dns-query"
        val bootstrapHosts = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
    }

    data object Quad9 : DohProvider {
        const val URL = "https://dns.quad9.net/dns-query"
        val bootstrapHosts = listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
    }

    data object AdGuard : DohProvider {
        const val URL = "https://dns.adguard-dns.com/dns-query"
        val bootstrapHosts = listOf("94.140.14.14", "94.140.15.15")
    }

    data class Custom(
        val url: String,
        val bootstrapHosts: List<String> = emptyList(),
    ) : DohProvider
}

fun DohProvider.toDns(
    bootstrapClient: OkHttpClient,
    fallbackToSystem: Boolean = true,
): Dns {
    val (url, bootstrap) =
        when (this) {
            DohProvider.System -> return Dns.SYSTEM
            DohProvider.Cloudflare -> DohProvider.Cloudflare.URL to DohProvider.Cloudflare.bootstrapHosts
            DohProvider.Google -> DohProvider.Google.URL to DohProvider.Google.bootstrapHosts
            DohProvider.Quad9 -> DohProvider.Quad9.URL to DohProvider.Quad9.bootstrapHosts
            DohProvider.AdGuard -> DohProvider.AdGuard.URL to DohProvider.AdGuard.bootstrapHosts
            is DohProvider.Custom -> url to bootstrapHosts
        }

    val parsedUrl = url.toHttpUrl()
    require(parsedUrl.isHttps) { "DoH endpoint must use HTTPS" }

    val builder =
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(parsedUrl)
            .includeIPv6(true)

    if (bootstrap.isNotEmpty()) {
        builder.bootstrapDnsHosts(bootstrap.map(InetAddress::getByName))
    }

    val doh = builder.build()
    if (!fallbackToSystem) return doh

    return Dns { hostname ->
        try {
            doh.lookup(hostname)
        } catch (primary: UnknownHostException) {
            try {
                Dns.SYSTEM.lookup(hostname)
            } catch (_: UnknownHostException) {
                throw primary
            }
        }
    }
}
