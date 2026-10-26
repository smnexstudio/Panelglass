package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The size a model name gives away, and the warning that follows from it. */
class ModelSizeHintTest {
    @Test
    fun aSizeInTheNameIsRead() {
        assertEquals(1.0, ModelSizeHint.parameterBillions("Llama-3.2-1B-Instruct"))
        assertEquals(1.0, ModelSizeHint.parameterBillions("llama3.2:1b"))
        assertEquals(0.5, ModelSizeHint.parameterBillions("Qwen2.5-0.5B"))
        assertEquals(7.0, ModelSizeHint.parameterBillions("qwen2.5-7b-instruct"))
        assertEquals(8.0, ModelSizeHint.parameterBillions("SEA-LION-v3.5-8B"))
        assertEquals(7.0, ModelSizeHint.parameterBillions("mistral-7b-v0.1"))
    }

    @Test
    fun aNameThatSaysNoSizeGivesNone() {
        assertNull(ModelSizeHint.parameterBillions("gpt-4o"))
        assertNull(ModelSizeHint.parameterBillions("gemma-3-270m"))
        assertNull(ModelSizeHint.parameterBillions("phi-3.5-mini-instruct"))
        assertNull(ModelSizeHint.parameterBillions("x86b"))
    }

    @Test
    fun theLastSizeInTheNameWins() {
        // A name may carry more than one number: the 1B model is the one that was asked for. "Q4" is a quantisation,
        // not a size, so it is not read.
        assertEquals(1.0, ModelSizeHint.parameterBillions("Llama-3.2-1B-Instruct-Q4"))
        assertEquals(3.0, ModelSizeHint.parameterBillions("7b-model-3b-variant"))
    }
}
