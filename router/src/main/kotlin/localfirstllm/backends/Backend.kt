/*
 * The backend abstraction: the one interface the router talks to. Where the
 * tokens actually come from — an on-device AICore model, an OpenAI-compatible
 * endpoint, the native Anthropic Messages API — is invisible above this line.
 *
 * Mirrors local-first-llm's `backends/types.ts` `Backend` interface, reduced to
 * the non-streaming `complete()` surface for 0.1.0 (streaming is on the
 * roadmap — see CHANGELOG).
 */
package localfirstllm.backends

import localfirstllm.ChatRequest
import localfirstllm.ChatResponse

/**
 * A source of chat completions. Implementations translate the library's
 * [ChatRequest] to and from their own wire shape.
 *
 * Implementations must be safe to call from a coroutine on any dispatcher;
 * HTTP-backed ones move blocking I/O to [kotlinx.coroutines.Dispatchers.IO]
 * internally.
 */
public interface Backend {
    /** Short identifier used in routing reasons and error messages, e.g. `"anthropic"`. */
    public val name: String

    /** Run one completion. Throws on transport/HTTP failure (see [BackendHttpException]). */
    public suspend fun complete(request: ChatRequest): ChatResponse
}

/**
 * Thrown by HTTP-based backends on a non-2xx response. The router treats any
 * throw from a backend as a signal to attempt its single bounded fallback (when
 * policy permits); callers that disable fallback see this directly.
 */
public class BackendHttpException(
    public val backend: String,
    public val status: Int,
    public val body: String,
) : Exception("$backend request failed with HTTP $status: ${body.take(500)}")
