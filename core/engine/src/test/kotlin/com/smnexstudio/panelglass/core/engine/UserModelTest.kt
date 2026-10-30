package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.UserModel
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserModelTest {
    @Test fun theContextBudgetIsReadFromTheFileName() {
        assertEquals(4096, UserModel.maxTokensFromFileName("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"))
        assertEquals(1280, UserModel.maxTokensFromFileName("model_ekv1280.task"))
        assertEquals(2048, UserModel.maxTokensFromFileName("model_without_a_budget.litertlm"))
    }

    @Test fun theContextBudgetIsClampedToWhatTheRuntimeCanRun() {
        assertEquals(UserModel.MIN_TOKENS, UserModel.clampTokens(16))
        assertEquals(UserModel.MAX_TOKENS, UserModel.clampTokens(999_999))
        assertEquals(4096, UserModel.clampTokens(4096))
    }

    @Test fun theFileNameIsBuiltFromTheIdAndNeverFromAPath() {
        val name = UserModel.fileNameFor("h0123456789ab")
        assertEquals("user-h0123456789ab.litertlm", name)
        assertFalse(name.contains("/"), name)
        assertFalse(name.contains(".."), name)
        assertFalse(name.contains("\\"), name)
    }

    @Test fun theIdsTellHubModelsAndImportedFilesApart() {
        val sha = "abcdef0123456789abcdef"
        assertEquals("habcdef012345", UserModel.hubId(sha))
        assertEquals("fabcdef012345", UserModel.fileId(sha))
    }

    @Test fun aModelSurvivesASaveAndRead() {
        val m = UserModel(
            id = "h0123456789ab", label = "Qwen 2.5 1.5B", fileName = UserModel.fileNameFor("h0123456789ab"),
            url = "https://huggingface.co/a/b/resolve/c/d.litertlm", bytes = 1_597_931_520L,
            sha256 = "a".repeat(64), maxTokens = 4096, source = "litert-community/Qwen2.5-1.5B-Instruct",
        )
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(m, json.decodeFromString<UserModel>(json.encodeToString(m)))
        // A field from a newer version is ignored, not a crash.
        val withExtra = json.encodeToString(m).replace("{", "{\"futureField\":1,")
        assertEquals(m, json.decodeFromString<UserModel>(withExtra))
    }

    @Test fun aModelRunsOnTheOfflineEngine() {
        val m = UserModel("h1", "L", "user-h1.litertlm", "", 10L, "a".repeat(64), 2048)
        assertEquals(EngineId.LOCAL_USER, m.engineId)
        assertEquals("usermodel:h1", m.key)
        assertTrue(EngineId.LOCAL_USER.isLocalLlm)
    }

    @Test fun aTruncatedFileIsRefused() {
        val m = UserModel("h1", "L", "user-h1.litertlm", "", 1000L, "a".repeat(64), 2048)
        assertEquals(700L, m.minValidBytes)
    }
}
