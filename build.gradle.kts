import groovy.util.Node
import groovy.xml.XmlParser
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URL


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}
group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val libraryVersion = getNextVersion().toString()

repositories {
    google()
    mavenCentral()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    js {
        outputModuleName.set("buffer-kt")
        browser()
        nodejs()
    }

    wasmJs {
        browser()
        nodejs()
    }
    macosX64()
    macosArm64()
    linuxX64()
    linuxArm64()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    watchosArm64()
    watchosSimulatorArm64()
    watchosX64()
    tvosArm64()
    tvosSimulatorArm64()
    tvosX64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
                implementation(libs.androidx.test.core.ktx)
                implementation(libs.androidx.test.ext.junit)
            }
        }

        jsMain.dependencies {
            implementation(libs.kotlin.web)
            implementation(libs.kotlin.js)
        }
    }
}

android {
    buildFeatures {
        aidl = true
    }
    compileSdk = 34
    defaultConfig {
        minSdk = 16
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    namespace = "$group.${rootProject.name}"
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

val publishedGroupId: String by project
val libraryName: String by project
val artifactName: String by project
val libraryDescription: String by project
val siteUrl: String by project
val gitUrl: String by project
val licenseName: String by project
val licenseUrl: String by project
val developerOrg: String by project
val developerName: String by project
val developerEmail: String by project
val developerId: String by project

project.group = publishedGroupId
project.version = libraryVersion

val signingInMemoryKey = project.findProperty("signingInMemoryKey")
val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword")
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"

if (isMainBranchGithub && signingInMemoryKey is String && signingInMemoryKeyPassword is String) {
    signing {
        useInMemoryPgpKeys(
            signingInMemoryKey,
            signingInMemoryKeyPassword,
        )
        sign(publishing.publications)
    }
}

mavenPublishing {
    if (isMainBranchGithub && signingInMemoryKey is String && signingInMemoryKeyPassword is String) {
        publishToMavenCentral()

        signAllPublications()
    }

    coordinates(publishedGroupId, artifactName, libraryVersion)

    pom {
        name.set(libraryName)
        description.set(libraryDescription)
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
    android.set(true)
}

class Version(
    val major: UInt,
    val minor: UInt,
    val patch: UInt,
    val snapshot: Boolean,
) {
    constructor(string: String, snapshot: Boolean) :
        this(
            string.split('.')[0].toUInt(),
            string.split('.')[1].toUInt(),
            string.split('.')[2].toUInt(),
            snapshot,
        )

    fun incrementMajor() = Version(major + 1u, 0u, 0u, snapshot)

    fun incrementMinor() = Version(major, minor + 1u, 0u, snapshot)

    fun incrementPatch() = Version(major, minor, patch + 1u, snapshot)

    fun snapshot() = Version(major, minor, patch, true)

    fun isVersionZero() = major == 0u && minor == 0u && patch == 0u

    override fun toString(): String =
        if (snapshot) {
            "$major.$minor.$patch-SNAPSHOT"
        } else {
            "$major.$minor.$patch"
        }
}
private var latestVersion: Version? = Version(0u, 0u, 0u, true)

@Suppress("UNCHECKED_CAST")
fun getLatestVersion(): Version {
    val latestVersion = latestVersion
    if (latestVersion != null && !latestVersion.isVersionZero()) {
        return latestVersion
    }
    val xml = URL("https://repo1.maven.org/maven2/com/ditchoom/${rootProject.name}/maven-metadata.xml").readText()
    val versioning = XmlParser().parseText(xml)["versioning"] as List<Node>
    val latestStringList = versioning.first()["latest"] as List<Node>
    val result = Version((latestStringList.first().value() as List<*>).first().toString(), false)
    this.latestVersion = result
    return result
}

fun getNextVersion(snapshot: Boolean = !isRunningOnGithub): Version {
    var v = getLatestVersion()
    if (snapshot) {
        v = v.snapshot()
    }
    if (project.hasProperty("incrementMajor") && project.property("incrementMajor") == "true") {
        return v.incrementMajor()
    } else if (project.hasProperty("incrementMinor") && project.property("incrementMinor") == "true") {
        return v.incrementMinor()
    }
    return v.incrementPatch()
}

tasks.create("nextVersion") {
    println(getNextVersion())
}

val signingTasks = tasks.withType<Sign>()
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(signingTasks)
}
