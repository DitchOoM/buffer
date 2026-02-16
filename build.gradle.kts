// Root build file for multi-module buffer project.
// Module-specific configuration is in buffer/build.gradle.kts, buffer-compression/build.gradle.kts,
// and buffer-flow/build.gradle.kts

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.atomicfu) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlinx.benchmark) apply false
}

// Aggregate tasks for convenience
tasks.register("allTests") {
    description = "Run tests for all modules and platforms"
    group = "verification"
    dependsOn(":buffer:allTests", ":buffer-compression:allTests", ":buffer-flow:allTests")
}

tasks.register("buildAll") {
    description = "Build all modules"
    group = "build"
    dependsOn(":buffer:build", ":buffer-compression:build", ":buffer-flow:build")
}

// Copy Dokka output to Docusaurus static directory
tasks.register<Copy>("copyDokkaToDocusaurus") {
    description = "Generate and copy API documentation to Docusaurus"
    group = "documentation"
    dependsOn(":buffer:dokkaGenerateHtml", ":buffer-compression:dokkaGenerateHtml", ":buffer-flow:dokkaGenerateHtml")

    from(layout.projectDirectory.dir("buffer/build/dokka/html")) {
        into("buffer")
    }
    from(layout.projectDirectory.dir("buffer-compression/build/dokka/html")) {
        into("buffer-compression")
    }
    from(layout.projectDirectory.dir("buffer-flow/build/dokka/html")) {
        into("buffer-flow")
    }
    into(layout.projectDirectory.dir("docs/static/api"))
}
