package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.local.HfCatalog
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The Hugging Face catalogue against a MockWebServer. The fixtures are trimmed copies of real responses:
 * a search array and a repository file tree.
 */
class HfCatalogTest {
    private lateinit var server: MockWebServer
    private val catalog = HfCatalog(testSupport())

    @BeforeEach fun start() { server = MockWebServer(); server.start(); catalog.apiBase = server.url("").toString().trimEnd('/') }
    @AfterEach fun stop() { server.shutdown() }

    /** A real search response, trimmed: the fields the catalogue reads. */
    private val searchJson = """
        [{"id":"litert-community/Gemma3-1B-IT","downloads":9908,"likes":1068},
         {"id":"litert-community/gemma-4-E2B-it-litert-lm","downloads":1444697,"likes":500}]
    """.trimIndent()

    /** A real file tree, trimmed: a `.litertlm` file with an LFS oid, a plain file and a directory. */
    private val treeJson = """
        [{"type":"file","oid":"c37adec9","size":2244,"path":".gitattributes"},
         {"type":"file","oid":"8d7b4db5","size":6182436864,
          "lfs":{"oid":"708483abb477c10dd8db8cc6b4995bbd9cd2deda5539249a37c218028a669391","size":6182436864},
          "path":"Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"},
         {"type":"file","oid":"f3dc6a4b","size":100,
          "lfs":{"oid":"edec401a8dd8de5409a5a1618e73ade59385d7a275b383b5c664e99882949c6d","size":100},
          "path":"notes.txt"},
         {"type":"directory","oid":"aaaa1111","path":"extra"}]
    """.trimIndent()

    @Test
    fun searchReturnsTheRepositoriesOfTheAuthor() = runBlocking {
        server.enqueue(MockResponse().setBody(searchJson))
        val repos = catalog.search("Gemma")
        assertEquals(2, repos.size)
        assertEquals("litert-community/Gemma3-1B-IT", repos[0].id)
        assertEquals(9908L, repos[0].downloads)
        assertFalse(repos[0].gated)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/models?author=litert-community"), req.path!!)
        assertTrue(req.path!!.contains("search=Gemma"), req.path!!)
    }

    @Test
    fun aGatedRepositoryIsMarkedAsSuch() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"a/b","downloads":1,"gated":"auto"}]"""))
        assertTrue(catalog.search("").single().gated)
    }

    @Test
    fun listingKeepsOnlyLitertlmFilesWithAnLfsHash() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"sha":"5068b3fd9788a4ed5633dc868687f778143b73f3","id":"litert-community/Qwen2.5-1.5B-Instruct"}"""))
        server.enqueue(MockResponse().setBody(treeJson))
        val listing = catalog.listing("litert-community/Qwen2.5-1.5B-Instruct")
        assertEquals("5068b3fd9788a4ed5633dc868687f778143b73f3", listing.commit)
        // The plain file, the directory and the file without an LFS oid are all left out.
        assertEquals(1, listing.files.size)
        val f = listing.files.single()
        assertEquals("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", f.path)
        assertEquals(6182436864L, f.bytes)
        assertEquals("708483abb477c10dd8db8cc6b4995bbd9cd2deda5539249a37c218028a669391", f.sha256)
    }

    @Test
    fun aFileWithoutAHashIsNotListed() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"sha":"abc"}"""))
        server.enqueue(MockResponse().setBody("""[{"type":"file","oid":"x","size":10,"path":"m.litertlm"}]"""))
        assertEquals(0, catalog.listing("a/b").files.size)
    }

    @Test
    fun theDownloadAddressIsPinnedToTheCommitAndEscaped() {
        val listing = HfCatalog.HfListing("litert-community/Some Repo", "deadbeef", emptyList())
        val file = HfCatalog.HfFile("dir name/model file.litertlm", 10, "a".repeat(64))
        val url = catalog.downloadUrl(listing, file)
        assertTrue(url.endsWith("/resolve/deadbeef/dir%20name/model%20file.litertlm"), url)
    }

    @Test
    fun onlyHubAddressesAreDownloaded() {
        assertTrue(catalog.isHubUrl("https://huggingface.co/a/b/resolve/c/d.litertlm"))
        assertFalse(catalog.isHubUrl("https://evil.example.com/a/b.litertlm"))
        assertFalse(catalog.isHubUrl("http://huggingface.co/a/b.litertlm"))
    }

    @Test
    fun aRepositoryIdIsOwnerSlashNameAndNothingElse() {
        assertTrue(catalog.looksLikeRepoId("a/b"))
        assertTrue(catalog.looksLikeRepoId("litert-community/Qwen2.5-1.5B-Instruct"))
        assertFalse(catalog.looksLikeRepoId("a"))
        assertFalse(catalog.looksLikeRepoId("a/b/c"))
        assertFalse(catalog.looksLikeRepoId("../x"))
        assertFalse(catalog.looksLikeRepoId("a b/c"))
        assertFalse(catalog.looksLikeRepoId(""))
    }

    @Test
    fun aRepositoryIdIsRefusedBeforeAnyRequest() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { catalog.listing("not a repo") } }
        assertEquals(0, server.requestCount)
    }
}
