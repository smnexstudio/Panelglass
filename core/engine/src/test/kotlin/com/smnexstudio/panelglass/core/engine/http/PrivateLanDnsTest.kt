package com.smnexstudio.panelglass.core.engine.http

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrivateLanDnsTest {
    private fun fakeDns(block: () -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = block()
    }
    @Test fun privateAndLoopbackAddressesAreAllowed() {
        for (ip in listOf("10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.2", "127.0.0.1", "::1", "fd00::1"))
            assertTrue(PrivateLanDns.allowed(InetAddress.getByName(ip)), ip)
    }
    @Test fun publicAndSpecialAddressesAreRejected() {
        for (ip in listOf("8.8.8.8", "0.0.0.0", "169.254.169.254", "100.64.0.1", "172.15.0.1", "172.32.0.1", "224.0.0.1", "::", "fe80::1", "2001:4860:4860::8888"))
            assertFalse(PrivateLanDns.allowed(InetAddress.getByName(ip)), ip)
    }
    @Test fun mixedDnsAnswersAreRejected() {
        val dns = PrivateLanDns(fakeDns { listOf(InetAddress.getByName("192.168.1.2"), InetAddress.getByName("8.8.8.8")) })
        assertThrows(UnknownHostException::class.java) { dns.lookup("server.lan") }
    }
    @Test fun emptyDnsAnswersAreRejected() {
        assertThrows(UnknownHostException::class.java) { PrivateLanDns(fakeDns { emptyList() }).lookup("server.lan") }
    }
    @Test fun validatedAnswersAreReturnedUnchanged() {
        val addresses = listOf(InetAddress.getByName("192.168.1.2"))
        assertEquals(addresses, PrivateLanDns(fakeDns { addresses }).lookup("server.lan"))
    }
}
