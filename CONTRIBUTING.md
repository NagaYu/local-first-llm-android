# Contributing to local-first-llm-android

Thanks for your interest! This is a Kotlin/Android library published to Maven Central. The bar is production quality: clear code, honest doc comments, and tests for anything with logic.

## Build & test

Requires JDK 17 and the Android SDK (`compileSdk 36`, `minSdk 26`).

```bash
./gradlew build                      # core module + example app
./gradlew :router:testDebugUnitTest  # the core unit tests (the important ones)
```

The core module (`:router`) builds and tests from a clean clone with **zero portfolio dependencies**.

### The optional `router-aicoreradar` module

`:router-aicoreradar` depends on the sibling artifact `io.github.nagayu:aicore-radar`, published separately. It is **opt-in** so the default build stays dependency-free:

```bash
./gradlew build -PwithAicoreRadar
```

This resolves `aicore-radar` from Maven Central once it is published there. Until then, build the sibling into your local Maven cache first. It is on a newer Gradle/AGP toolchain, so use **its own** Gradle wrapper:

```bash
( cd ../aicore-radar && ./gradlew publishToMavenLocal )
./gradlew :router-aicoreradar:testDebugUnitTest -PwithAicoreRadar
```

CI mirrors this: the core job always runs; the integration job auto-detects whether `aicore-radar` is on Maven Central and cleanly skips (with a notice) until it is.

## Testing philosophy

- **`decide()` is the highest-priority test surface.** It is a pure function with full branch coverage in [`PolicyTest.kt`](router/src/test/kotlin/localfirstllm/PolicyTest.kt) — every taxonomy row × capability level, both sides of the heuristic thresholds, the sensitive×viability pairs, and budget switches in both directions. Add to it whenever you touch the policy.
- **Backends are tested with fixtures, not live network calls** — MockWebServer for the HTTP backends, and pure request/response-mapping tests for the Anthropic wire translation. No test hits a real API.
- **The capability→level reduction** in `router-aicoreradar` is a pure function tested with hand-constructed `aicore-radar` signals.

## Real-device testing for `AICoreBackend` (not CI-automatable)

`AICoreBackend` and `BuiltinCapabilityProvider.aicore()` talk to the on-device AICore service through ML Kit's GenAI Prompt API. **This cannot be exercised on CI** — it requires:

- a physical, **eligible** device (Gemini Nano's capable tiers need a flagship chip and 12 GB+ RAM — an emulator will not do),
- the AICore model **downloaded** (the first download can require Wi-Fi and take minutes),
- your app in the **foreground** (AICore blocks background inference).

So the ML Kit-touching classes are kept deliberately thin, and all routing/mapping logic lives in the pure, tested layers above them. To verify the on-device path manually:

1. Build and install the example app on an eligible device: `./gradlew :app-example:installDebug`.
2. Ensure the on-device model is available (the sibling `aicore-radar` produces a device report that tells you exactly why if it is not).
3. Launch the app and confirm a `Classification`/`Summarization` request reports `routedTo = OnDevice`.

The ML Kit API surface used here mirrors the sibling `aicore-radar`'s device-verified usage at the same pinned `genai-prompt` version. This API is in beta and evolving — when bumping the version, re-verify the class/method names.

## Style

- Kotlin official code style; `explicitApi()` is on for the library modules — every public declaration needs an explicit visibility and a doc comment.
- Match the surrounding comment density and naming. Doc comments should explain *why*, and must not describe behavior the code doesn't have.

## Publishing (maintainers)

`./gradlew publishAndReleaseToMavenCentral` for both modules, with Central Portal credentials and a signing key configured via the standard `mavenCentralUsername` / `mavenCentralPassword` / `signing.*` Gradle properties. `build` and `test` never trigger signing or upload.
