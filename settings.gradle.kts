pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
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
