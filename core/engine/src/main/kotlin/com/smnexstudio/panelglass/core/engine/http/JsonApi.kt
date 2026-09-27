package com.smnexstudio.panelglass.core.engine.http

import android.util.Log
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One Retrofit surface for every provider: absolute URLs, per-call headers, JSON in and out. */
interface JsonApi {
    @POST
    suspend fun post(@Url url: String, @HeaderMap headers: Map<String, String>, @Body body: JsonObject): Response<JsonElement>

    @GET
    suspend fun get(@Url url: String, @HeaderMap headers: Map<String, String>): Response<JsonElement>
}

/**
 * Shared HTTP plumbing for the paid engines: performs the call and maps status codes onto the
 * typed [EngineFailure] set. Response bodies are never logged; they may echo the key.
 */
@Singleton
class HttpEngineSupport @Inject constructor(
    val okHttp: OkHttpClient,
    val json: Json,
) {
    val api: JsonApi = Retrofit.Builder()
        .baseUrl("https://localhost/")
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(JsonApi::class.java)

    suspend fun post(engine: EngineId, url: String, headers: Map<String, String>, body: JsonObject): JsonElement {
        val resp = try {
            api.post(url, headers, body)
        } catch (e: IOException) {
            // Class name only: the message can contain the URL, which for some providers carries the key.
            Log.w(TAG, "${engine.name}: ${e.javaClass.simpleName}")
            throw EngineException(EngineFailure.Network(engine), e)
        }
        if (resp.isSuccessful) return resp.body() ?: throw EngineException(EngineFailure.Malformed(engine))
        val errBody = runCatching { resp.errorBody()?.string() }.getOrNull().orEmpty()
        throw EngineException(mapStatus(engine, resp.code(), errBody, resp.headers()["Retry-After"]))
    }

    suspend fun get(engine: EngineId, url: String, headers: Map<String, String>): JsonElement {
        val resp = try {
            api.get(url, headers)
        } catch (e: IOException) {
            Log.w(TAG, "${engine.name}: ${e.javaClass.simpleName}")
            throw EngineException(EngineFailure.Network(engine), e)
        }
        if (resp.isSuccessful) return resp.body() ?: throw EngineException(EngineFailure.Malformed(engine))
        val errBody = runCatching { resp.errorBody()?.string() }.getOrNull().orEmpty()
        throw EngineException(mapStatus(engine, resp.code(), errBody, resp.headers()["Retry-After"]))
    }

    /** A cheap HEAD-like request so the pool holds a warm TLS connection to the provider. */
    fun warm(url: String) {
        try {
            okHttp.newCall(Request.Builder().url(url).head().build()).execute().close()
        } catch (_: Exception) { }
    }

    companion object {
        private const val TAG = "HttpEngine"

        fun mapStatus(engine: EngineId, code: Int, body: String, retryAfter: String?): EngineFailure {
            val lower = body.lowercase()
            return when (code) {
                401, 403 -> EngineFailure.MissingKey(engine)
                402 -> EngineFailure.QuotaExceeded(engine)
                429 -> if ("quota" in lower || "billing" in lower || "insufficient" in lower || "exceeded your current" in lower) EngineFailure.QuotaExceeded(engine)
                else EngineFailure.RateLimited(engine, (retryAfter?.toLongOrNull()?.times(1000)) ?: 2_000)
                456 -> EngineFailure.QuotaExceeded(engine) // DeepL: character limit reached
                // Any other 400 is the provider refusing *our request* (an option the chosen model does not support, an
                // unknown field): the provider's reason is what the user needs, not "unusable reply".
                400 -> if ("api key" in lower || "api_key" in lower) EngineFailure.MissingKey(engine)
                else EngineFailure.Unavailable(engine, "HTTP 400" + (errorMessage(body)?.let { ": $it" } ?: ""))
                500, 502, 503, 504, 529 -> EngineFailure.Overloaded(
                    engine, (retryAfter?.toLongOrNull()?.times(1000)) ?: 1_000,
                    "HTTP $code" + (errorMessage(body)?.let { ": $it" } ?: ""),
                )
                else -> EngineFailure.Unavailable(engine, "HTTP $code" + (errorMessage(body)?.let { ": $it" } ?: ""))
            }
        }

        /**
         * The provider's own explanation (`error.message`, or `error` when it is a string), for the failure row: a 404
         * reads very differently as "model not found for API version" than as a proxy's HTML page. Never logged;
         * anything that looks like a credential is stripped and the rest is cut short.
         */
        fun errorMessage(body: String): String? {
            val text = runCatching {
                val el = Json.parseToJsonElement(body)
                val err = (el as? JsonObject)?.get("error") ?: return@runCatching null
                (err as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content } ?: (err as? JsonPrimitive)?.content
            }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) return null
            val clean = text.replace(SECRET_LIKE, "…").replace(Regex("\\s+"), " ")
            return if (clean.length > MAX_MESSAGE) clean.take(MAX_MESSAGE) + "…" else clean
        }

        private val SECRET_LIKE = Regex("[A-Za-z0-9_\\-]{24,}")
        private const val MAX_MESSAGE = 140
    }
}
