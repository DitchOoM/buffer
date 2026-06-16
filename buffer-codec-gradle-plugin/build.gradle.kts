plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
    signing
}

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true

apply(from = "../gradle/setup.gradle.kts")

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
// Honor -Pversion so local publishes can pin a version (e.g. -Pversion=4.3.0-SNAPSHOT)
// without being clobbered by Maven Central's getNextVersion auto-increment.
if (!project.hasProperty("version") || project.version == "unspecified") {
    project.version = getNextVersion(!isRunningOnGithub).toString()
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
    // The descriptor model, parser, and drift classifier are the single format authority,
    // shared with the KSP processor so the emitter and this differ can never drift apart.
    implementation(project(":buffer-codec-schema"))
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("codecSchema") {
            id = "com.ditchoom.buffer.codec-schema"
            implementationClass = "com.ditchoom.buffer.codec.gradle.CodecSchemaPlugin"
            displayName = "Buffer Codec Schema"
            description =
                "Baselines the wire-format descriptor emitted by buffer-codec and classifies " +
                "schema drift (checkCodecSchema / updateCodecSchema)."
        }
    }
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

val publishedGroupId: String by project
val libraryName: String by project
val siteUrl: String by project
val gitUrl: String by project
val licenseName: String by project
val licenseUrl: String by project
val developerOrg: String by project
val developerName: String by project
val developerEmail: String by project
val developerId: String by project

project.group = publishedGroupId

val signingInMemoryKey = project.findProperty("signingInMemoryKey")
val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword")
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val shouldSignAndPublish = isMainBranchGithub && signingInMemoryKey is String && signingInMemoryKeyPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(
            signingInMemoryKey as String,
            signingInMemoryKeyPassword as String,
        )
        sign(publishing.publications)
    }
}

mavenPublishing {
    if (shouldSignAndPublish) {
        publishToMavenCentral()
        signAllPublications()
    }

    coordinates(publishedGroupId, "buffer-codec-gradle-plugin", project.version.toString())

    pom {
        name.set("Buffer Codec Schema Gradle Plugin")
        description.set(
            "Gradle plugin that baselines the buffer-codec wire-format descriptor and fails the " +
                "build on breaking schema drift (checkCodecSchema / updateCodecSchema).",
        )
        url.set(siteUrl)

        licenses {
            license {
                name.set(licenseName)
                url.set(licenseUrl)
            }
        }
        developers {
            developer {
                id.set(developerId)
                name.set(developerName)
                email.set(developerEmail)
            }
        }
        organization {
            name.set(developerOrg)
        }
        scm {
            connection.set(gitUrl)
            developerConnection.set(gitUrl)
            url.set(siteUrl)
        }
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/generated/**")
    }
}

dokka {
    dokkaSourceSets.configureEach {
        reportUndocumented.set(false)
    }
}
