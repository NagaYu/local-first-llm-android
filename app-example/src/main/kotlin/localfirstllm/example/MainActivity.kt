package localfirstllm.example

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import localfirstllm.BuiltinCapabilityProvider
import localfirstllm.ChatRequest
import localfirstllm.HybridRouter
import localfirstllm.Message
import localfirstllm.PrivacyLevel
import localfirstllm.RoutedResponse
import localfirstllm.Role
import localfirstllm.RoutingBlockedException
import localfirstllm.TaskHint
import localfirstllm.aicore
import localfirstllm.backends.AICoreBackend
import localfirstllm.backends.OpenAICompatibleBackend

/**
 * Minimal example: wire a [HybridRouter] with the on-device AICore backend and
 * an OpenAI-compatible cloud backend, then run a few requests that the policy
 * routes differently, printing where each one went and why.
 *
 * This is intentionally dependency-light (no AppCompat / Compose) so it reads as
 * a usage sample, not an app template. It never crashes without a device or an
 * API key — each request's outcome (or error) is printed to the screen.
 */
class MainActivity : Activity() {

    // The example UI is a single scrolling text log, built in code.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val output = TextView(this).apply {
            textSize = 13f
            setPadding(32, 32, 32, 32)
            text = "Running local-first-llm-android example…\n"
        }
        setContentView(ScrollView(this).apply { addView(output) })

        // ── Build the router ────────────────────────────────────────────────
        // Replace the cloud backend's baseUrl/apiKey/model with your own. For a
        // production app, keep the key server-side and point baseUrl at a proxy.
        val router = HybridRouter(
            capability = BuiltinCapabilityProvider.aicore(),
            onDevice = AICoreBackend(),
            cloud = OpenAICompatibleBackend(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "REPLACE_WITH_YOUR_KEY",
                model = "gpt-4o-mini",
            ),
        )

        scope.launch {
            log(output, "\n1) Classification (policy prefers on-device):")
            runOne(
                output,
                router,
                ChatRequest(
                    messages = listOf(Message(Role.User, "Is 'the refund never arrived' positive or negative?")),
                    hint = TaskHint.Classification,
                    maxTokens = 16,
                ),
            )

            log(output, "\n2) Open-ended chat (policy prefers cloud):")
            runOne(
                output,
                router,
                ChatRequest(
                    messages = listOf(Message(Role.User, "Write a two-line poem about the tide.")),
                    hint = TaskHint.Chat,
                ),
            )

            log(output, "\n3) Sensitive request (hard rule: on-device or blocked, never cloud):")
            runOne(
                output,
                router,
                ChatRequest(
                    messages = listOf(Message(Role.User, "Summarize this private note: …")),
                    hint = TaskHint.Summarization,
                    privacy = PrivacyLevel.Sensitive,
                ),
            )
        }
    }

    private suspend fun runOne(output: TextView, router: HybridRouter, request: ChatRequest) {
        try {
            val result: RoutedResponse = router.complete(request)
            log(output, "  routedTo = ${result.routedTo}")
            log(output, "  reason   = ${result.routingReason}")
            log(output, "  message  = ${result.message.content?.take(200) ?: "(no text)"}")
        } catch (blocked: RoutingBlockedException) {
            log(output, "  BLOCKED (${blocked.code}): ${blocked.reason}")
        } catch (error: Throwable) {
            // e.g. no API key set, or AICore unavailable on this device.
            log(output, "  backend error: ${error.message}")
        }
    }

    private suspend fun log(output: TextView, line: String) = withContext(Dispatchers.Main) {
        output.append("$line\n")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
