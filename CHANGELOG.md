# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — 2026-07-12

First release.

### Added

- **Policy engine** (`decide()`) — a pure, synchronous, fully unit-tested routing
  function faithfully ported from the TypeScript sibling
  [`local-first-llm`](https://github.com/NagaYu/local-first-llm): privacy gate as
  a hard rule, task-type taxonomy, no-hint request-shape heuristic, three-value
  capability cross-check, and cost/latency budget checks. Every decision carries
  a human-readable reason.
- **`HybridRouter`** — orchestration behind a single `suspend complete()`:
  cached capability probe, `decide()`, backend dispatch, and a single bounded
  fallback that never routes a sensitive request to the cloud. Every result
  carries `routedTo` and `routingReason`.
- **Backends** — `AICoreBackend` (on-device via ML Kit's GenAI Prompt API),
  `OpenAICompatibleBackend` (any OpenAI-wire endpoint), and `AnthropicBackend`
  (the native Anthropic Messages API, with explicit request/response mapping for
  its four divergences from the OpenAI wire).
- **Capability providers** — a built-in AICore availability floor
  (`BuiltinCapabilityProvider.aicore()`) in the core module, plus the optional
  `router-aicoreradar` module's `AICoreRadarCapabilityProvider`, which maps
  [`aicore-radar`](https://github.com/NagaYu/aicore-radar)'s measured signals to
  the three-value `CapabilityLevel`.
- **Multi-module publishing** — `router` (core, zero portfolio dependencies) and
  `router-aicoreradar` (optional) as independently consumable Maven Central
  artifacts.
- **Example app** and CI (unit tests for both modules).

### Known limitations

- **Non-streaming only.** `complete()` is implemented across all backends;
  streaming responses are on the roadmap.
- **On-device is single-turn/text.** `AICoreBackend` flattens a conversation to a
  prompt and does not do on-device tool calling or JSON-mode structured output;
  route those to a cloud backend via hints.
- **`AICoreBackend` is device-only** and not covered by CI — see
  [CONTRIBUTING.md](CONTRIBUTING.md).

[0.1.0]: https://github.com/NagaYu/local-first-llm-android/releases/tag/v0.1.0
