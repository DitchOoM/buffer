import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    id("dev.petuska.npm.publish") version "3.4.1"
    kotlin("multiplatform") version "1.9.20"
    id("com.android.library")
    id("io.codearte.nexus-staging") version "0.30.0"
    `maven-publish`
    signing
    id("org.jlleitschuh.gradle.ktlint") version "11.5.1"
    id("org.jlleitschuh.gradle.ktlint-idea") version "11.5.1"
}

val libraryVersionPrefix: String by project
group = "com.ditchoom"
version = "$libraryVersionPrefix.0-SNAPSHOT"
val libraryVersion = if (System.getenv("GITHUB_RUN_NUMBER") != null) {
    "$libraryVersionPrefix${(Integer.parseInt(System.getenv("GITHUB_RUN_NUMBER")) - 56)}"
} else {
    "${libraryVersionPrefix}0-SNAPSHOT"
}

repositories {
    google()
    mavenCentral()
    maven { setUrl("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers/") }
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        testRuns["test"].executionTask.configure {
            useJUnit()
        }
    }
    js(IR) {
        moduleName = "buffer-kt"
        browser { binaries.library() }
        nodejs { binaries.library() }
    }

    macosX64()
    macosArm64()
    linuxX64()
    ios()
    iosSimulatorArm64()
    tasks.getByName<KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
        device.set("iPhone 14")
    }
    watchos()
    watchosSimulatorArm64()
    tvos()
    tvosSimulatorArm64()

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        val jvmMain by getting {
        }
        val jvmTest by getting {
            kotlin.srcDir("src/commonJvmTest/kotlin")
        }

        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlin-wrappers:kotlin-web:1.0.0-pre.615")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-js:1.0.0-pre.615")
            }
        }
        val jsTest by getting
        val macosX64Main by getting
        val macosX64Test by getting
        val macosArm64Main by getting
        val macosArm64Test by getting
        val linuxX64Main by getting
        val linuxX64Test by getting
        val iosMain by getting
        val iosTest by getting
        val iosSimulatorArm64Main by getting
        val iosSimulatorArm64Test by getting
        val watchosMain by getting
        val watchosTest by getting
        val watchosSimulatorArm64Main by getting
        val watchosSimulatorArm64Test by getting
        val tvosMain by getting
        val tvosTest by getting
        val tvosSimulatorArm64Main by getting
        val tvosSimulatorArm64Test by getting

        val nativeMain by sourceSets.creating {
            dependsOn(commonMain)
            linuxX64Main.dependsOn(this)
        }
        val nativeTest by sourceSets.creating {
            dependsOn(commonTest)
            linuxX64Test.dependsOn(this)
        }

        val appleMain by sourceSets.creating {
            dependsOn(commonMain)
            macosX64Main.dependsOn(this)
            macosArm64Main.dependsOn(this)
            iosMain.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            watchosMain.dependsOn(this)
            watchosSimulatorArm64Main.dependsOn(this)
            tvosMain.dependsOn(this)
            tvosSimulatorArm64Main.dependsOn(this)
        }

        val appleTest by sourceSets.creating {
            dependsOn(commonTest)
            macosX64Test.dependsOn(this)
            macosArm64Test.dependsOn(this)
            iosTest.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
            watchosTest.dependsOn(this)
            watchosSimulatorArm64Test.dependsOn(this)
            tvosTest.dependsOn(this)
            tvosSimulatorArm64Test.dependsOn(this)
        }

        val androidMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        val androidUnitTest by getting {
            kotlin.srcDir("src/commonJvmTest/kotlin")
        }
        val androidInstrumentedTest by getting {
            dependsOn(commonTest)
            kotlin.srcDir("src/commonJvmTest/kotlin")
            kotlin.srcDir("src/commonTest/kotlin")
            dependencies {
                implementation("androidx.test:runner:1.5.2")
                implementation("androidx.test:rules:1.5.0")
                implementation("androidx.test:core-ktx:1.5.0")
            }
        }

        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
            languageSettings.optIn("kotlinx.cinterop.UnsafeNumber")
        }
    }
}

android {
    compileSdk = 34
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["androidTest"].manifest.srcFile("src/androidAndroidTest/AndroidManifest.xml")
    defaultConfig {
        minSdk = 16
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    namespace = "$group.${rootProject.name}"
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

(System.getenv("GITHUB_REPOSITORY"))?.let {
    if (System.getenv("GITHUB_REF") == "refs/heads/main") {
        signing {
            useInMemoryPgpKeys(
                "56F1A973",
                System.getenv("GPG_SECRET"),
                System.getenv("GPG_SIGNING_PASSWORD")
            )
            sign(publishing.publications)
        }
    }

    val ossUser = System.getenv("SONATYPE_NEXUS_USERNAME")
    val ossPassword = System.getenv("SONATYPE_NEXUS_PASSWORD")

    val publishedGroupId: String by project
    val libraryName: String by project
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

    publishing {
        publications.withType(MavenPublication::class) {
            groupId = publishedGroupId
            version = libraryVersion

            artifact(tasks["javadocJar"])

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

        repositories {
            maven("https://oss.sonatype.org/service/local/staging/deploy/maven2/") {
                name = "sonatype"
                credentials {
                    username = ossUser
                    password = ossPassword
                }
            }
        }
    }

    nexusStaging {
        username = ossUser
        password = ossPassword
        packageGroup = publishedGroupId
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
}
