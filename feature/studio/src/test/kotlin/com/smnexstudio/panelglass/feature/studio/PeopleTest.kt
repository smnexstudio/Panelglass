package com.smnexstudio.panelglass.feature.studio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Several authors or artists in one field. */
class PeopleTest {
    @Test fun namesSplitOnCommasAndSemicolonsAnyScript() {
        assertEquals(listOf("qwerty", "Zxcv Bnm"), People.split(" qwerty ,Zxcv Bnm "))
        assertEquals(listOf("A", "B", "C"), People.split("A; B;;C,"))
        assertEquals(listOf("尾田", "栄一郎"), People.split("尾田、栄一郎"))
        assertEquals(listOf("A"), People.split("A, A"), "a repeated name is shown once")
    }

    @Test fun oneNameOrNoneStaysAsItIs() {
        assertEquals(listOf("Hirohiko Araki"), People.split("Hirohiko Araki"))
        assertEquals(emptyList<String>(), People.split("  "))
    }
}
