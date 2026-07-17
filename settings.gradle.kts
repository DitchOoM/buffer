pluginManagement {
    repositories {
        // The com.ditchoom.boringssl.provision plugin is not on the Plugin Portal yet. For local /
        // alien1 validation it is published to mavenLocal from boringssl-kmp (:boringssl-provision:
        // publishToMavenLocal). Listed first so that local build wins; drop once the plugin is on the
        // Portal. TODO(provision-release): remove mavenLocal() when the plugin is published.
        mavenLocal()
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    // Pin the provision plugin version from a property so the same build drives dev/alien1 + a future
    // published release. Default matches the mavenLocal dev publish (0.0.1-SNAPSHOT).
    val provisionVersion = providers.gradleProperty("boringsslPluginVersion").orNull ?: "0.0.1-SNAPSHOT"
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.ditchoom.boringssl.provision") useVersion(provisionVersion)
        }
    }
}

rootProject.name = "buffer"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version ("4.4.2")
}
develocity {
    buildScan {
        uploadInBackground.set(System.getenv("CI") != null)
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set("yes")
    }
}

// Monorepo modules
include(":buffer")
include(":buffer-compression")
include(":buffer-flow")
include(":buffer-codec")
include(":buffer-codec-schema")
include(":buffer-codec-processor")
include(":buffer-codec-gradle-plugin")
include(":buffer-codec-test")
include(":buffer-1brc")
include(":buffer-crypto")
include(":buffer-kotlinx-io")
include(":buffer-ktor")
include(":buffer-okio")
include(":buffer-sqldelight")
