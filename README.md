# local-first-llm-android

**Per-request on-device/cloud LLM routing for native Android — provider-agnostic, not wedded to any one SDK.**

[![CI](https://github.com/NagaYu/local-first-llm-android/actions/workflows/ci.yml/badge.svg)](https://github.com/NagaYu/local-first-llm-android/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.nagayu/local-first-llm-android)](https://central.sonatype.com/artifact/io.github.nagayu/local-first-llm-android)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A small, chat-completion-shaped Kotlin API that decides, **per request**, whether to run on-device (via AICore / ML Kit GenAI) or in the cloud (via any provider) — using a validated routing policy: a task-type taxonomy, privacy-sensitivity as a hard rule, capability-awareness, and optional cost/latency budgets.

## The gap this fills

Firebase AI Logic already ships a hybrid on-device/cloud inference pattern for Android (`PREFER_ON_DEVICE` / `ONLY_ON_DEVICE` / `PREFER_IN_CLOUD` / `ONLY_IN_CLOUD`). It validates the concept — but it locks you into **Firebase** as the backend and **Gemini** as the model family on both sides.

As one analysis of Android's on-device AI story put it: *"The abstraction layer that handles both [on-device and cloud], without wedding the codebase to Google's SDK, is the actual engineering work."* This library is that abstraction, open and provider-agnostic: on-device via AICore, cloud via **any** OpenAI-compatible endpoint or the **native Anthropic Messages API**, with the routing decision as a pure, testable function you can inspect and override.

## Quickstart

```kotlin
val router = HybridRouter(
    capability = BuiltinCapabilityProvider.aicore(),          // built-in AICore availability check
    onDevice = AICoreBackend(),                                // ML Kit GenAI (Gemini Nano)
    cloud = OpenAICompatibleBackend(
        baseUrl = "https://api.openai.com/v1",
        apiKey = "…",
        model = "gpt-4o-mini",
    ),
    // policy = PolicyConfig(),                                // optional overrides
)

val result = router.complete(
    ChatRequest(
        messages = listOf(Message(Role.User, "Is this review positive or negative?")),
        hint = TaskHint.Classification,          // optional task-type hint
        privacy = PrivacyLevel.Sensitive,        // optional — Sensitive never leaves the device
    ),
)

result.routedTo       // RouteMode.OnDevice or RouteMode.Cloud
result.routingReason  // human-readable trail, e.g. "hint=Classification, capability=OnDeviceFull; …"
result.message        // the assistant Message
```

`complete()` is a `suspend` function — call it from a coroutine.

### Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.nagayu:local-first-llm-android:0.1.0")               // core — zero portfolio deps
    // implementation("io.github.nagayu:local-first-llm-android-aicoreradar:0.1.0") // optional, recommended (see below)
}
```

## How routing is decided

`decide(input, config)` is a pure, synchronous, fully unit-tested function. It runs these steps **in order**:

1. **Privacy gate (hard rule).** `PrivacyLevel.Sensitive` is served on-device when the device is viable, otherwise **`Blocked`** — it is *never* sent to the cloud, regardless of hints, capability quality, or budgets.
2. **Preferred mode** from the taxonomy table (below), or a request-shape heuristic when no hint is given.
3. **Capability cross-check.** An on-device preference falls back to cloud only when the device is `NotViable`; `OnDeviceDegraded` still routes on-device.
4. **Budget check (last).** If the chosen mode is estimated to blow a set cost/latency budget and the other viable mode fits, switch.

### Default taxonomy

| Task hint | Default preferred mode | Why |
|---|---|---|
| `Classification` | on-device | short in/out, well within small-model strengths |
| `Extraction` | on-device | structured, bounded-length output |
| `Summarization` | on-device | generally fine — but very long source documents are a known reason to override toward cloud |
| `Chat` | cloud | open-ended quality bar; inconsistency is user-visible |
| `Reasoning` | cloud | small on-device models degrade on multi-step reasoning |
| `Creative` | cloud | long-form fluency benefits from larger models |
| *(no hint)* | heuristic | short input **+** no tools **+** modest max output → treat like `Classification`; otherwise like `Chat` |

Every row is overridable via `PolicyConfig.taxonomyOverrides`; thresholds, cost/latency estimates, and default budgets are overridable too. Every decision carries a human-readable `reason`.

## Backends

All implement one `Backend` interface (`suspend fun complete(request): ChatResponse`):

- **`AICoreBackend`** — on-device, over ML Kit's GenAI Prompt API (AICore / Gemini Nano). Single-turn, text-oriented for 0.1.0.
- **`OpenAICompatibleBackend`** — any OpenAI-wire endpoint (OpenAI, OpenRouter, LiteLLM, vLLM, Ollama, …).
- **`AnthropicBackend`** — the **native** Anthropic Messages API (not the OpenAI-compat shim, which Anthropic documents as for testing). It handles the four wire divergences explicitly: `max_tokens` is required (a default is injected), the system prompt is a top-level parameter (not a `system` message), tool calls are `tool_use`/`tool_result` content blocks (not `tool_calls`), and `stop_reason` is mapped to OpenAI-style finish reasons. `response_format: json_object` is intentionally **not** translated (no lossless Messages-API equivalent).

Resilience: if the chosen backend throws, the router retries **once** against the other mode when policy permits — bounded, configurable, and **never** cloud for a sensitive request.

## Capability providers

- **Built-in (`router` core):** `BuiltinCapabilityProvider.aicore()` — a basic AICore availability check with **no benchmarking**. `AVAILABLE` → `OnDeviceFull`, everything else → `NotViable`; it never reports `OnDeviceDegraded`. Honest about being a **floor**, not a good signal.
- **Recommended (`router-aicoreradar`, optional):** `AICoreRadarCapabilityProvider` maps [aicore-radar](https://github.com/NagaYu/aicore-radar)'s *measured* signals — quota headroom, measured decode speed, measured context window — down to the three-value `CapabilityLevel`, so `OnDeviceDegraded` becomes a real, earned state.

## Modules & dependencies

- **`router`** — the core. **Zero dependency** on `aicore-radar` or any other portfolio project. This is all you need.
- **`router-aicoreradar`** — an optional, separately-published artifact that depends on both `router` and `aicore-radar`. It exists as its own module precisely so consumers who want only the core never pull in `aicore-radar`. Enable it in the build with `-PwithAicoreRadar` (see [CONTRIBUTING.md](CONTRIBUTING.md)).

## Prior art

- **Firebase AI Logic hybrid inference** — the closest existing analog; it validates the pattern but is Firebase/Gemini-locked (and Chrome-desktop-only on the web).
- **[`local-first-llm`](https://github.com/NagaYu/local-first-llm)** — this project's TypeScript/browser sibling, which established and validated the routing policy design ported here. The Kotlin `decide()` is a faithful port of its decision tree.
- **[`aicore-radar`](https://github.com/NagaYu/aicore-radar)** — also by this author; the recommended measured capability signal for the optional `router-aicoreradar` module. The relationship is an optional module dependency, not a core one.

## Status

`0.1.0` — first release. Non-streaming `complete()` across all backends; streaming is on the roadmap. See [CHANGELOG.md](CHANGELOG.md).

## License

[MIT](LICENSE).
