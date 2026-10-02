package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class StageInvalidationTest {
    private val done = StudioStage.entries.toSet()

    @Test fun aReshapeOrStrokeClearsCleanedOnly() {
        val after = StageRules.afterCleanInputChange(done)
        assertFalse(StudioStage.CLEANED in after)
        assertEquals(done - StudioStage.CLEANED, after)
    }

    @Test fun editingTextOrStyleClearsNothing() = assertEquals(done, StageRules.afterTextOrStyleEdit(done))
}
