package pl.gpschat

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Runs the same Kotlin HTTP/JSON client as the app against the actual Python API. */
class ApiClientIntegrationTest {
    private lateinit var server: Process
    private lateinit var temporary: java.nio.file.Path
    private lateinit var address: String

    @Before fun startActualBackend() {
        temporary = Files.createTempDirectory("gpschat-client-test")
        val python = """
            from server import make_server
            s = make_server(port=0, database=r'${temporary.resolve("messages.sqlite3")}')
            print(s.server_port, flush=True)
            s.serve_forever()
        """.trimIndent()
        server = ProcessBuilder("python3", "-u", "-c", python)
            .directory(java.io.File(requireNotNull(System.getProperty("gpschat.backend")) { "Missing backend directory for integration tests" }))
            .redirectError(temporary.resolve("server.log").toFile()).start()
        val port = server.inputStream.bufferedReader().readLine().toInt()
        address = "http://127.0.0.1:$port"
    }

    @After fun stopBackend() {
        if (::server.isInitialized) {
            server.destroy()
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly()
        }
        if (::temporary.isInitialized) temporary.toFile().deleteRecursively()
    }

    private fun client(character: Char, url: String = address) = ApiClient(object : ApiConfiguration {
        override val apiUrl = url
        override val token = character.toString().repeat(64)
    })

    @Test fun twoAnonymousClientsShareUnicodeTextAndEnforceOwnership() = runBlocking {
        val author = client('a')
        val reader = client('b')
        author.health()
        val message = author.post("Zażółć gęślą jaźń 🌿", 52.2297, 21.0122)
        assertTrue(message.mine)
        val nearby = reader.nearby(52.2297, 21.0122)
        assertEquals(message.text, nearby.single().text)
        assertFalse(nearby.single().mine)
        try {
            reader.delete(message.id)
            fail("Another installation must not delete the author's message")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("własną"))
        }
        author.delete(message.id)
        assertTrue(reader.nearby(52.2297, 21.0122).isEmpty())
    }

    @Test fun arrivalLookupDoesNotReturnWithdrawnMessage() = runBlocking {
        val author = client('a')
        val reader = client('b')
        val message = author.post("Do odczytania w pobliżu", 0.0, 0.0)
        assertEquals(message.id, reader.lookup(listOf(message.id)).single().id)
        author.delete(message.id)
        assertTrue(reader.lookup(listOf(message.id)).isEmpty())
    }

    @Test fun serverValidationErrorsReachTheUser() = runBlocking {
        try {
            client('a').post(" ", 0.0, 0.0)
            fail("Empty text must be rejected by the API")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("500 znaków"))
        }
    }

    @Test fun reportingUsesTheSharedApi() = runBlocking {
        val message = client('a').post("Zgłaszany wpis", 0.0, 0.0)
        client('b').report(message.id)
        client('b').report(message.id) // Idempotent on the server.
    }

    @Test fun redirectsCannotLeakInstallationCredential() = runBlocking {
        val marker = temporary.resolve("forwarded")
        val code = """
            from http.server import HTTPServer, BaseHTTPRequestHandler
            from pathlib import Path
            class Handler(BaseHTTPRequestHandler):
                def do_GET(self):
                    if self.path == '/health':
                        self.send_response(302)
                        self.send_header('Location', 'http://127.0.0.1:%s/stolen' % self.server.server_port)
                        self.end_headers()
                    else:
                        Path(r'$marker').write_text('forwarded')
                        self.send_response(200)
                        self.end_headers()
                        self.wfile.write(b'{"status":"ok"}')
                def log_message(self, *args): pass
            s = HTTPServer(('127.0.0.1', 0), Handler)
            print(s.server_port, flush=True)
            s.serve_forever()
        """.trimIndent()
        val redirect = ProcessBuilder("python3", "-u", "-c", code)
            .redirectError(temporary.resolve("redirect.log").toFile()).start()
        val port = redirect.inputStream.bufferedReader().readLine().toInt()
        try {
            try {
                client('a', "http://127.0.0.1:$port").health()
                fail("Redirect must be rejected")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("302"))
            }
            assertFalse(Files.exists(marker))
        } finally { redirect.destroy(); redirect.waitFor(5, TimeUnit.SECONDS) }
    }
}
