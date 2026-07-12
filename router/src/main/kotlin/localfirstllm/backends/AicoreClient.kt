/*
 * Internal wrapper around ML Kit's GenAI Prompt API (`com.google.mlkit:genai-prompt`).
 *
 * The exact class/method surface used here is the one verified against a real
 * device and the shipped bytecode by the sibling `aicore-radar` project
 * (see its docs/API-VERIFICATION.md) at the same pinned version
 * (genai-prompt 1.0.0-beta2). This API is in beta and evolving — when bumping
 * the version, re-verify these names. Docs: https://developers.google.com/ml-kit/genai
 *
 * Nothing here is unit-tested on CI: it requires the AICore service and an
 * eligible device (see CONTRIBUTING.md). It is kept deliberately thin so almost
 * all logic lives in the pure, tested layers above it.
 */
package localfirstllm.backends

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import localfirstllm.AicoreAvailabilityStatus

/**
 * Which release stage of the on-device model to use. Mirrors ML Kit's
 * `ModelReleaseStage` without leaking its type into this library's API.
 * `Preview` models require enrollment in the AICore Developer Preview program.
 */
public enum class AicoreModelStage { Stable, Preview }

/**
 * Which model variant to use. Mirrors ML Kit's `ModelPreference`. Availability
 * is answered per variant — a device can be eligible for `Fast` but not `Full`.
 */
public enum class AicoreModelPreference { Full, Fast }

/** Thin, closeable handle over a ML Kit [GenerativeModel]. */
internal class AicoreClient(private val model: GenerativeModel) : AutoCloseable {

    /** Maps `checkStatus()` to the library's provider-agnostic availability enum. */
    suspend fun checkStatus(): AicoreAvailabilityStatus = mapStatus(model.checkStatus())

    /** Non-streaming inference; returns the first candidate's text (may be empty). */
    suspend fun generateContent(prompt: String): String =
        model.generateContent(GenerateContentRequest.Builder(TextPart(prompt)).build())
            .firstCandidateText()

    /** Total (input + output) token limit reported by the device. */
    suspend fun tokenLimit(): Int = model.getTokenLimit()

    override fun close(): Unit = model.close()

    companion object {
        fun create(
            stage: AicoreModelStage = AicoreModelStage.Stable,
            preference: AicoreModelPreference = AicoreModelPreference.Full,
        ): AicoreClient = AicoreClient(Generation.getClient(generationConfig(stage, preference)))

        private fun generationConfig(
            stage: AicoreModelStage,
            preference: AicoreModelPreference,
        ): GenerationConfig = GenerationConfig.Builder()
            .apply {
                modelConfig = ModelConfig.Builder()
                    .apply {
                        releaseStage = when (stage) {
                            AicoreModelStage.Stable -> ModelReleaseStage.STABLE
                            AicoreModelStage.Preview -> ModelReleaseStage.PREVIEW
                        }
                        this.preference = when (preference) {
                            AicoreModelPreference.Full -> ModelPreference.FULL
                            AicoreModelPreference.Fast -> ModelPreference.FAST
                        }
                    }
                    .build()
            }
            .build()

        /** `FeatureStatus` (an IntDef) → availability enum; unknown integers → [AicoreAvailabilityStatus.Unavailable]. */
        fun mapStatus(status: Int): AicoreAvailabilityStatus = when (status) {
            FeatureStatus.AVAILABLE -> AicoreAvailabilityStatus.Available
            FeatureStatus.DOWNLOADABLE -> AicoreAvailabilityStatus.Downloadable
            FeatureStatus.DOWNLOADING -> AicoreAvailabilityStatus.Downloading
            else -> AicoreAvailabilityStatus.Unavailable
        }
    }
}

private fun GenerateContentResponse.firstCandidateText(): String =
    candidates.firstOrNull()?.text.orEmpty()
