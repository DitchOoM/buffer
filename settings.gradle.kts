pluginManagement {
    repositories {
        // The com.ditchoom.boringssl.provision plugin is not on the Plugin Portal yet; it currently
        // ships only as the boringssl-kmp v0.0.1-rc.1 PRERELEASE. Both consumption paths resolve it
        // through mavenLocal (~/.m2/repository):
        //   * local / alien1: :boringssl-provision:publishToMavenLocal from boringssl-kmp (0.0.1-SNAPSHOT).
        //   * CI: each Gradle-invoking job unzips the release's maven-local-staging.zip into
        //     ~/.m2/repository (see .github/actions/boringssl-prerelease) and pins 0.0.1-rc.1 via
        //     -PboringsslPluginVersion.
        // The content filter scopes mavenLocal to ONLY the com.ditchoom.boringssl.* groups (the plugin
        // marker `com.ditchoom.boringssl.provision` + the impl group `com.ditchoom.boringssl`), so an
        // unrelated artifact sitting in a developer's ~/.m2 can never shadow a real Portal/Central
        // dependency through this entry — a bare mavenLocal() would let it. mavenLocal (not a plain
        // maven{} dir repo) is kept deliberately: it honors -SNAPSHOT `-local` metadata for the dev
        // publish. Remove entirely once the plugin ships to the Plugin Portal / Maven Central (stable).
        mavenLocal {
            content { includeGroupByRegex("com\\.ditchoom\\.boringssl.*") }
        }
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
