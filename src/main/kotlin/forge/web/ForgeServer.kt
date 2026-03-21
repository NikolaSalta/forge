package forge.web

import forge.ForgeConfig
import forge.core.Orchestrator
import forge.llm.AgentOrchestrator
import forge.llm.ModelSelector
import forge.llm.OllamaClient
import forge.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/**
 * Embedded Ktor HTTP server that serves both the web UI and REST API.
 * Replaces the previous Node.js server.js with direct Kotlin service calls,
 * eliminating command injection and SQL injection vulnerabilities.
 *
 * Binds to 127.0.0.1 only — not accessible from external network.
 */
class ForgeServer(
    private val config: ForgeConfig,
    private val ollamaClient: OllamaClient,
    private val workspaceManager: WorkspaceManager,
    private val modelSelector: ModelSelector,
    private val orchestrator: Orchestrator,
    private val agentOrchestrator: AgentOrchestrator? = null,
    private val port: Int = 3456
) {
    private val webState = WebState(workspaceManager, config)
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /**
     * Start the server (non-blocking by default).
     */
    fun start(wait: Boolean = false) {
        server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            configurePlugins()
            configureRouting()
        }.also {
            it.start(wait = wait)
        }
        println()
        println("  FORGE Web Dashboard running at http://localhost:$port")
        println("  Repo: ${webState.repoPath ?: "(none selected)"}")

        // Preload always-hot agents on startup
        if (agentOrchestrator != null && config.agents.preloadOnStartup) {
            runBlocking {
                try {
                    agentOrchestrator.initialize()
                    println("  Agents: ${config.agents.alwaysHot.joinToString(" + ")} preloaded")
                } catch (e: Exception) {
                    println("  Agents: preload failed (${e.message})")
                }
            }
        }
        println()
    }

    /**
     * Stop the server gracefully.
     */
    fun stop() {
        webState.close()
        server?.stop(1000, 2000)
    }

    /**
     * Block until the server shuts down (useful for desktop mode).
     */
    fun waitForShutdown() {
        Thread.currentThread().join()
    }

    // ── Plugin configuration ────────────────────────────────────────────────

    private fun Application.configurePlugins() {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }

        install(CORS) {
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Options)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.Accept)
            exposeHeader(HttpHeaders.ContentType)
            // Only allow localhost origins (secure default)
            allowHost("localhost:$port")
            allowHost("127.0.0.1:$port")
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                val message = cause.message ?: "Internal server error"
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error = message))
            }
        }
    }

    // ── Routing configuration ────────────────────────────────────────────────

    private fun Application.configureRouting() {
        routing {
            // Serve static web UI from classpath resources
            get("/") {
                val html = this::class.java.classLoader.getResourceAsStream("web/index.html")
                if (html != null) {
                    call.respondBytes(html.readBytes(), ContentType.Text.Html)
                } else {
                    call.respondText("FORGE Web UI not found in resources", status = HttpStatusCode.NotFound)
                }
            }

            // Install all API routes
            apiRoutes(
                webState = webState,
                config = config,
                ollamaClient = ollamaClient,
                workspaceManager = workspaceManager,
                modelSelector = modelSelector,
                orchestrator = orchestrator,
                agentOrchestrator = agentOrchestrator
            )
        }
    }
}
