package com.smnexstudio.panelglass.core.engine.local

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "LocalLlmEngine"
/** After a cancel the native side still has to unwind before its conversation may be closed. */
private const val CANCEL_DRAIN_MS = 5_000L

/**
 * A `.litertlm` model through LiteRT-LM, the runtime of Google's AI Edge Gallery. The file carries its own chat
 * template, so the prompt goes in as a system instruction plus one user turn of a fresh [com.google.ai.edge.litertlm.Conversation]
 * per completion (no history between calls). The GPU backend is tried first and the CPU is the fallback, as in the
 * Gallery; emulators, and [cpuOnly] (Settings › Run Qwen on the CPU), go straight to the CPU. LiteRT-LM exposes no
 * tokenizer, so [tokens] is null and the engine budgets by characters.
 */
class LiteRtLmRunner(context: Context, modelPath: String, maxTokens: Int, cpuOnly: Boolean = false) : LlmRunner {
    private val engine: Engine
    val backend: String

    init {
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
        val candidates = if (cpuOnly || isEmulator()) listOf("cpu") else listOf("gpu", "cpu")
        var loaded: Engine? = null
        var used = ""
        for (name in candidates) {
            val started = System.currentTimeMillis()
            val e = Engine(EngineConfig(modelPath = modelPath, backend = if (name == "gpu") Backend.GPU() else Backend.CPU(), maxNumTokens = maxTokens, cacheDir = cache))
            try {
                e.initialize()
                loaded = e; used = name
                android.util.Log.i(TAG, "LiteRT-LM loaded on $name in ${System.currentTimeMillis() - started} ms")
                break
            } catch (t: Exception) {
                android.util.Log.w(TAG, "LiteRT-LM $name backend failed: ${t::class.simpleName}")
                runCatching { e.close() }
            }
        }
        engine = loaded ?: throw IllegalStateException("LiteRT-LM could not load the model")
        backend = used
    }

    override suspend fun complete(prompt: ChatPrompt): String = complete(prompt) { false }

    override suspend fun complete(prompt: ChatPrompt, done: (String) -> Boolean): String {
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(prompt.system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2),
            ),
        )
        val acc = StringBuilder()
        val stopped = AtomicBoolean(false)
        val ended = CountDownLatch(1)
        val started = System.currentTimeMillis()
        var chunks = 0
        try {
            val out = suspendCancellableCoroutine<String> { cont ->
                conversation.sendMessageAsync(prompt.user, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val piece = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        val soFar = synchronized(acc) {
                            chunks++
                            // Streamed pieces are deltas; a runtime that resent the whole text so far is handled too.
                            if (acc.isNotEmpty() && piece.length >= acc.length && piece.startsWith(acc)) acc.setLength(0)
                            acc.append(piece); acc.toString()
                        }
                        if (!stopped.get() && done(soFar) && stopped.compareAndSet(false, true)) {
                            ForkJoinPool.commonPool().execute { runCatching { conversation.cancelProcess() } }
                        }
                    }

                    override fun onDone() {
                        ended.countDown()
                        if (cont.isActive) cont.resume(synchronized(acc) { acc.toString() })
                    }

                    override fun onError(throwable: Throwable) {
                        ended.countDown()
                        if (!cont.isActive) return
                        // A generation we stopped ourselves may end as an error: the streamed text is the reply.
                        if (stopped.get()) cont.resume(synchronized(acc) { acc.toString() }) else cont.resumeWithException(throwable)
                    }
                })
                cont.invokeOnCancellation { runCatching { conversation.cancelProcess() } }
            }
            android.util.Log.i(
                TAG,
                "generate ($backend): reply ${out.length} chars / $chunks chunks in ${System.currentTimeMillis() - started} ms" +
                    if (stopped.get()) " (stopped early)" else "",
            )
            return out
        } finally {
            runCatching { ended.await(CANCEL_DRAIN_MS, TimeUnit.MILLISECONDS) }
            runCatching { conversation.close() }
        }
    }

    override fun close() { engine.close() }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk_gphone")
}
