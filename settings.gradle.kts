pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "buffer"
plugins {
    id("com.gradle.develocity") version ("3.17.3")
}
develocity {
    buildScan {
        uploadInBackground.set(System.getenv("CI") != null)
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set("yes")
    }
}
