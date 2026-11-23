package com.smnexstudio.panelglass.core.engine.http

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Validate exact DNS results for the LAN transport; never fall back to public addresses. */
internal class PrivateLanDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !allowed(it) }) {
            throw UnknownHostException("Private LAN addresses required")
        }
        return addresses
    }

    companion object {
        /** RFC1918 and loopback IPv4; unique-local and loopback IPv6. No link-local/CGNAT. */
        fun allowed(address: InetAddress): Boolean {
            val b = address.address.map { it.toInt() and 255 }
            return when (b.size) {
                4 -> b[0] == 10 || b[0] == 127 ||
                    (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
                16 -> (b[0] and 254) == 252 || (b.take(15).all { it == 0 } && b[15] == 1)
                else -> false
            }
        }
    }
}
