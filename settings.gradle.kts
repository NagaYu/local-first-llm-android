pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Opt-in for the AICore-radar integration module (see below).
val withAicoreRadar = providers.gradleProperty("withAicoreRadar").isPresent

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only when building the optional integration: allow consuming a
        // locally-published aicore-radar (see below). Kept out of the default
        // build so the core never resolves from the local Maven cache.
        if (withAicoreRadar) {
            mavenLocal()
        }
    }
}

rootProject.name = "local-first-llm-android"

// The core library and the example app build from a clean clone with no
// portfolio prerequisites.
include(":router")

// Optional AICore-capability-aware integration. It depends on the sibling
// artifact `io.github.nagayu:aicore-radar`, published separately — so it is
// opt-in and not part of the default clean-clone build:
//
//   ./gradlew build                    # core only, zero portfolio deps
//   ./gradlew build -PwithAicoreRadar  # + router-aicoreradar
//
// The integration resolves `io.github.nagayu:aicore-radar` from Maven Central
// once it is published there. Until then, build the sibling into your local
// Maven cache first (it is on a newer Gradle/AGP, so use ITS OWN wrapper):
//
//   ( cd ../aicore-radar && ./gradlew publishToMavenLocal )
//   ./gradlew build -PwithAicoreRadar
if (withAicoreRadar) {
    include(":router-aicoreradar")
}
