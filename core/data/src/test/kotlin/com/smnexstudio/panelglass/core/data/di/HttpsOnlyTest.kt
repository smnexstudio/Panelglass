package com.smnexstudio.panelglass.core.data.di

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class HttpsOnlyTest {
    @Test fun aCleartextRequestIsRejectedBeforeItReachesTheServer() {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(HttpsOnly()).addNetworkInterceptor(HttpsOnly()).build()
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(server.url("/")).build()).execute().close() }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
}
