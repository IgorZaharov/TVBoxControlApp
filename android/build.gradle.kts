plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// The version code of both apps (docs/RELEASE_SIGNING.md, "Upgrades"). It must grow from build to build, so that
// each build installs over the one before it of the same kind (debug, release APK, Google Play bundle).
// The release workflow sets VERSION_CODE to its run number. Other builds use the number of commits on HEAD, so a
// newer commit gives a higher code. A shallow clone has only one commit: CI fetches the full history.
val appVersionCode: Int by extra(
    System.getenv("VERSION_CODE")?.toIntOrNull()
        ?: runCatching {
            providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
                .standardOutput.asText.get().trim().toInt()
        }.getOrDefault(1)
)
