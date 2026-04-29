plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    kotlin("jvm")
    alias(libs.plugins.ktlint)
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

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
}
