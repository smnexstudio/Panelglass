package com.smnexstudio.panelglass.core.data.di

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Refuses cleartext requests, including redirect targets when installed as a network interceptor. The update client
 * and every translation engine (`core:engine` `HttpEngineSupport`) use it.
 */
class HttpsOnly : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!chain.request().url.isHttps) throw IOException("HTTPS is required")
        return chain.proceed(chain.request())
    }
}
