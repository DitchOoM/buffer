@file:Suppress("UNUSED_VARIABLE")
import org.gradle.internal.os.OperatingSystem.*

plugins {
    kotlin("multiplatform") version "1.4.21"
    id("com.android.library")
    id("maven-publish")
}

group = "com.ditchoom"
version = "0.0-SNAPSHOT"

repositories {
    google()
    mavenCentral()
    jcenter()
}

kotlin {
    jvm()
    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        nodejs {
            testTask {

            }
        }
    }
    linuxX64()
    mingwX64()
    macosX64()
    ios {
        binaries {
            framework {
                baseName = "library"
            }
        }
    }
    watchos()
    tvos()
    android {
        publishLibraryVariants()
    }

    @Suppress("INACCESSIBLE_TYPE")
    val publicationsFromMainHost = when (current()) {
        WINDOWS -> {
            listOf(mingwX64()).map { it.name } + "kotlinMultiplatform"
        }
        LINUX -> {
            listOf(jvm(), js(), linuxX64(), android()).map { it.name } + "kotlinMultiplatform"
        }
        MAC_OS -> {
            listOf(jvm(), js(), android()).map { it.name } + "kotlinMultiplatform"
        }
        else -> throw IllegalStateException("Unsupported operating system ${current()}")
    }
    publishing {
        publications {
            matching { it.name in publicationsFromMainHost }.all {
                val targetPublication = this@all
                tasks.withType<AbstractPublishToMaven>()
                    .matching { it.publication == targetPublication }
                    .configureEach { onlyIf { findProperty("isMainHost") == "true" } }
            }
            publications.withType<MavenPublication>().all {
                pom {
                    name.set("Ditch OOM - Multiplatform ByteBuffer")
                    artifactId = "bytebuffer"
                    description.set("Multiplatform bytebuffer that delegates to native byte[] or ByteBuffer")
                    url.set("https://github.com/DitchOOM/ByteBuffer")
                    scm {
                        url.set("https://github.com/DitchOOM/ByteBuffer")
                        connection.set("scm:git:git://github.com/DitchOOM/ByteBuffer.git")
                        developerConnection.set("scm:git:git://github.com/DitchOOM/ByteBuffer.git")
                    }
                    licenses {
                        license {
                            name.set("The Apache Software License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("thebehera")
                            name.set("Rahul Behera")
                            url.set("https://github.com/thebehera")
                        }
                    }
                }
            }
        }
//        repositories {
//            maven {
//                setUrl("https://oss.sonatype.org/service/local/staging/deploy/maven2")
//                credentials {
//                    username = gradleLocalProperties(rootDir).getProperty("sonatypeUsername")
//                    password = gradleLocalProperties(rootDir).getProperty("sonatypePassword")
//                }
//            }
//        }
    }
    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.2")
            }
        }
        val jvmMain by getting
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        val androidMain by getting {
            dependsOn(jvmMain)
        }
        val androidTest by getting {
            dependsOn(jvmTest)
        }
        val jsMain by getting
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
        val nativeMain by creating {
            dependsOn(commonMain)
        }
        val nativeTest by creating {
            dependsOn(commonTest)
        }
        val linuxX64Main by getting {
            dependsOn(nativeMain)
        }
        val linuxX64Test by getting {
            dependsOn(nativeTest)
        }
        val mingwX64Main by getting {
            dependsOn(nativeMain)
        }
        val mingwX64Test by getting {
            dependsOn(nativeTest)
        }
        val macosX64Main by getting {
            dependsOn(nativeMain)
        }
        val macosX64Test by getting {
            dependsOn(nativeTest)
        }
        val iosArm64Main by getting {
            dependsOn(nativeMain)
        }
        val iosArm64Test by getting {
            dependsOn(nativeTest)
        }
        val iosX64Main by getting {
            dependsOn(nativeMain)
        }
        val iosX64Test by getting {
            dependsOn(nativeTest)
        }
        val tvosMain by getting {
            dependsOn(nativeMain)
        }
        val tvosTest by getting {
            dependsOn(nativeTest)
        }
        val watchosMain by getting {
            dependsOn(nativeMain)
        }
        val watchosTest by getting {
            dependsOn(nativeTest)
        }
    }
}

android {
    compileSdkVersion(30)
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdkVersion(1)
        targetSdkVersion(30)
    }
}