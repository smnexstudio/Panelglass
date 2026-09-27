package com.smnexstudio.panelglass.core.ocr

import ai.onnxruntime.OrtSession

/**
 * Session options shared by the on-device ONNX models (the detector and manga-ocr): CPU, all graph optimisations.
 *
 * XNNPACK is available in this ORT (`addXnnpack`) but off: on the x86_64 emulator it was slower for both models
 * (detector 314 -> 384-489 ms, manga-ocr decoder step 20 -> 37-51 ms). Its case is ARM; switch it on only after
 * [OrtBench] shows a win on a phone.
 */
internal object OrtCpu {
    /** Leave a core for the UI thread; past four the models stop scaling on phone big.LITTLE clusters. */
    val threads: Int = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors() - 1))

    fun options(xnnpack: Boolean, ortThreads: Int = threads): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setInterOpNumThreads(1)
        setIntraOpNumThreads(ortThreads)
        if (xnnpack) {
            // XNNPACK runs the ops it supports on its own pool; ORT's pool runs the rest. Spinning ORT threads would
            // fight XNNPACK's for the same cores.
            addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
    }
}
