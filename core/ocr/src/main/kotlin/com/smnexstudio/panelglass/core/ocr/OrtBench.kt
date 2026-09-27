package com.smnexstudio.panelglass.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer

/**
 * Debug tool, inert unless `files/debug-ortbench` exists (checked in [ComicTextDetector.warmUp]): times the detector
 * and manga-ocr under each [OrtCpu] variant and logs medians under the `OrtBench` tag. Model time does not depend on
 * pixel content, so a constant input is a fair comparison.
 */
internal object OrtBench {
    private const val TAG = "OrtBench"

    private class Variant(val name: String, val make: () -> OrtSession.SessionOptions)

    private val variants = listOf(
        Variant("cpu-t${OrtCpu.threads}") { OrtCpu.options(xnnpack = false) },
        Variant("xnn-ort1") { OrtCpu.options(xnnpack = true, ortThreads = 1) },
        Variant("xnn-ort${OrtCpu.threads}") { OrtCpu.options(xnnpack = true) },
    )

    fun run(detector: File, encoder: File?, decoder: File?) {
        val env = OrtEnvironment.getEnvironment()
        for (v in variants) {
            runCatching { detector(env, detector, v) }.onFailure { log("${v.name} detector FAILED ${it::class.simpleName}: ${it.message}") }
            if (encoder != null && decoder != null) {
                runCatching { mangaOcr(env, encoder, decoder, v) }.onFailure { log("${v.name} manga-ocr FAILED ${it::class.simpleName}: ${it.message}") }
            }
        }
    }

    private fun detector(env: OrtEnvironment, model: File, v: Variant) {
        val t0 = System.nanoTime()
        env.createSession(model.absolutePath, v.make()).use { s ->
            val load = ms(t0)
            val input = floats(3 * 640 * 640, 0.5f)
            val times = time(8) {
                OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 640, 640)).use { img ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1080, 2000)), longArrayOf(1, 2)).use { sz ->
                        s.run(mapOf("images" to img, "orig_target_sizes" to sz)).close()
                    }
                }
            }
            log("${v.name} detector load=${load}ms runs=$times median=${times.sorted()[times.size / 2]}ms")
        }
    }

    private fun mangaOcr(env: OrtEnvironment, encFile: File, decFile: File, v: Variant) {
        env.createSession(encFile.absolutePath, v.make()).use { enc ->
            env.createSession(decFile.absolutePath, v.make()).use { dec ->
                val px = floats(3 * 224 * 224, 0f)
                var encTimes: List<Long> = emptyList(); var decTimes: List<Long> = emptyList()
                OnnxTensor.createTensor(env, px, longArrayOf(1, 3, 224, 224)).use { pv ->
                    encTimes = time(6) { enc.run(mapOf("pixel_values" to pv)).close() }
                    enc.run(mapOf("pixel_values" to pv)).use { out ->
                        val hs = out.get(0) as OnnxTensor
                        // A 16-token prefix: a typical step in the middle of a bubble.
                        val ids = LongBuffer.wrap(LongArray(16) { if (it == 0) 2L else 100L + it })
                        decTimes = time(8) {
                            ids.rewind()
                            OnnxTensor.createTensor(env, ids, longArrayOf(1, 16)).use { t ->
                                dec.run(mapOf("input_ids" to t, "encoder_hidden_states" to hs)).close()
                            }
                        }
                    }
                }
                log("${v.name} manga-ocr encoder median=${encTimes.sorted()[encTimes.size / 2]}ms decoder-step@16 median=${decTimes.sorted()[decTimes.size / 2]}ms")
            }
        }
    }

    /** Two untimed runs (first-run allocations), then [n] timed ones. */
    private fun time(n: Int, block: () -> Unit): List<Long> {
        repeat(2) { block() }
        return List(n) { val t = System.nanoTime(); block(); ms(t) }
    }

    private fun floats(n: Int, v: Float) = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { b -> for (i in 0 until n) b.put(i, v) }
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    private fun log(s: String) { android.util.Log.i(TAG, s) }
}
