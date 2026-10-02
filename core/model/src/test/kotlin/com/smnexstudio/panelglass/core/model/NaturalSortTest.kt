package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NaturalSortTest {
    private fun sorted(vararg names: String) = names.toList().shuffled(java.util.Random(7)).sortedWith(NaturalOrder)

    @Test fun numbersCompareByValue() =
        assertEquals(listOf("1.png", "2.png", "10.png", "11.png", "100.png"), sorted("10.png", "2.png", "100.png", "1.png", "11.png"))

    @Test fun prefixedNumbers() =
        assertEquals(listOf("p8.jpg", "p9.jpg", "p10.jpg"), sorted("p10.jpg", "p9.jpg", "p8.jpg"))

    @Test fun zeroPaddedAndPlainMix() =
        assertEquals(listOf("page_001.webp", "page_2.webp", "page_010.webp"), sorted("page_010.webp", "page_2.webp", "page_001.webp"))

    @Test fun caseIsIgnored() =
        assertEquals(listOf("a1", "B2", "c3"), sorted("c3", "a1", "B2"))

    @Test fun severalNumberRuns() =
        assertEquals(listOf("v1c2p3", "v1c2p10", "v1c10p1", "v2c1p1"), sorted("v2c1p1", "v1c10p1", "v1c2p10", "v1c2p3"))

    @Test fun hugeRunsDoNotOverflow() =
        assertEquals(
            listOf("x99999999999999999999", "x100000000000000000000"),
            sorted("x100000000000000000000", "x99999999999999999999"),
        )
}
