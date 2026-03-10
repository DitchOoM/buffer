plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    kotlin("jvm")
    alias(libs.plugins.ktlint)
    `maven-publish`
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":buffer-codec-processor"))
}

val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
apply(from = "../gradle/setup.gradle.kts")

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()
group = "com.ditchoom"

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "buffer-codec-mqtt-spi"
        }
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
}
