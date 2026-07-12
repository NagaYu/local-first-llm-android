/*
 * On-device backend, backed by ML Kit's GenAI Prompt API (AICore / Gemini Nano).
 *
 * This is the `onDevice` half of the router. It is intentionally the thinnest
 * possible adapter over [AicoreClient]; it cannot be unit-tested on CI (it needs
 * the AICore service and an eligible device — see CONTRIBUTING.md), so all the
 * routing intelligence lives in the pure, tested layers above it.
 *
 * Scope for 0.1.0: the Prompt API is single-turn and text-oriented, so this
 * backend flattens the conversation to a plain-text prompt and does not support
 * tool calling or JSON-mode structured output on-device. Requests that need
 * those should be hinted/marked so the policy routes them to a cloud backend.
 */
package localfirstllm.backends

import localfirstllm.ChatRequest
import localfirstllm.ChatResponse
import localfirstllm.FinishReason
import localfirstllm.Message
import localfirstllm.Role

/**
 * @property stage Model release stage (STABLE by default; PREVIEW needs
 *   AICore Developer Preview enrollment).
 * @property preference Model variant (FULL by default; FAST is smaller/faster).
 * @property modelName Reported back in [ChatResponse.model]; purely cosmetic
 *   since the on-device model id is fixed by the device.
 * @property clientFactory Injection seam. Defaults to creating a real ML Kit
 *   client per call; a fresh client is cheap (creating it never touches the
 *   model — the model loads on first inference).
 */
public class AICoreBackend internal constructor(
    private val clientFactory: () -> AicoreClient,
    private val modelName: String,
) : Backend {

    public constructor(
        stage: AicoreModelStage = AicoreModelStage.Stable,
        preference: AicoreModelPreference = AicoreModelPreference.Full,
        modelName: String = "aicore-gemini-nano",
    ) : this({ AicoreClient.create(stage, preference) }, modelName)

    override val name: String = "aicore"

    override suspend fun complete(request: ChatRequest): ChatResponse {
        val prompt = flattenToPrompt(request.messages)
        val text = clientFactory().use { it.generateContent(prompt) }
        return ChatResponse(
            id = "aicore-${System.nanoTime()}",
            model = modelName,
            message = Message(role = Role.Assistant, content = text),
            // The Prompt API does not surface a structured stop reason; a
            // normally-returned completion is reported as a natural stop.
            finishReason = FinishReason.Stop,
            usage = null,
        )
    }

    internal companion object {
        /**
         * Flatten a multi-turn conversation into a single prompt string for the
         * single-turn Prompt API. System turns become a leading instruction
         * block; user/assistant turns are labeled so the model can follow the
         * exchange. This is a pragmatic mapping, not a chat template — the
         * on-device model has no role-aware formatting here.
         */
        fun flattenToPrompt(messages: List<Message>): String {
            val system = messages.filter { it.role == Role.System }.mapNotNull { it.content }
            val turns = messages.filter { it.role != Role.System }
            return buildString {
                if (system.isNotEmpty()) {
                    append(system.joinToString("\n\n"))
                    append("\n\n")
                }
                for (message in turns) {
                    val label = when (message.role) {
                        Role.User -> "User"
                        Role.Assistant -> "Assistant"
                        Role.Tool -> "Tool result"
                        Role.System -> "System"
                    }
                    message.content?.let {
                        append(label).append(": ").append(it).append('\n')
                    }
                }
                // Cue the model to produce the next assistant turn.
                append("Assistant:")
            }
        }
    }
}
