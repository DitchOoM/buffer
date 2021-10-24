import java.io.File
import java.io.FileInputStream
import java.util.*

plugins {
    kotlin("multiplatform") version "1.5.31"
//    id("com.android.library")
    id("io.codearte.nexus-staging") version "0.30.0"
    `maven-publish`
    signing
}

val libraryVersionPrefix: String by project
group = "com.ditchoom"
version = "$libraryVersionPrefix.0-SNAPSHOT"

repositories {
    google()
    mavenCentral()
}

kotlin {
//    android()
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        testRuns["test"].executionTask.configure {
            useJUnit()
        }
    }
    js {
        binaries.executable()
        browser {
            commonWebpackConfig {
                cssSupport.enabled = true
            }
        }
    }
    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosX64("hostOS")
        hostOs == "Linux" -> linuxX64("hostOS")
        isMingwX64 -> mingwX64("hostOS")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }
//    ios()
//    watchos()
//    tvos()
    val publicationsFromMainHost =
        listOf(jvm(), js(),nativeTarget).map { it.name } + "kotlinMultiplatform"
    publishing {
        publications {
            matching { it.name in publicationsFromMainHost }.all {
                val targetPublication = this@all
                tasks.withType<AbstractPublishToMaven>()
                    .matching { it.publication == targetPublication }
                    .configureEach { onlyIf { findProperty("isMainHost") == "true" } }
            }
        }
    }
    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
            }
        }
        val jvmMain by getting {
            kotlin.srcDir("src/commonJvmMain/kotlin")
        }
        val jvmTest by getting {
            kotlin.srcDir("src/commonJvmTest/kotlin")
        }
        val jsMain by getting
        val jsTest by getting
        val hostOSMain by getting
        val hostOSTest by getting
//        val iosMain by getting
//        val iosTest by getting
//        val watchosMain by getting
//        val watchosTest by getting
//        val tvosMain by getting
//        val tvosTest by getting

        val nativeMain by sourceSets.creating {
            dependsOn(commonMain)
            hostOSMain.dependsOn(this)
//            iosMain.dependsOn(this)
//            watchosMain.dependsOn(this)
//            tvosMain.dependsOn(this)
        }
        val nativeTest by sourceSets.creating {
            dependsOn(commonTest)
            hostOSTest.dependsOn(this)
//            iosTest.dependsOn(this)
//            watchosTest.dependsOn(this)
//            tvosTest.dependsOn(this)
        }

//        val androidMain by getting {
//            kotlin.srcDir("src/commonJvmMain/kotlin")
//        }
//        val androidTest by getting {
//            kotlin.srcDir("src/commonJvmTest/kotlin")
//        }
    }
}

//android {
//    compileSdkVersion(31)
//    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
//    defaultConfig {
//        minSdkVersion(1)
//        targetSdkVersion(31)
//    }
//    lintOptions {
//        isQuiet = true
//        isAbortOnError =  false
//    }
//    compileOptions {
//        sourceCompatibility = JavaVersion.VERSION_1_8
//        targetCompatibility = JavaVersion.VERSION_1_8
//    }
//}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

System.getenv("GITHUB_REPOSITORY")?.let {
    signing {

        useInMemoryPgpKeys("56F1A973", System.getenv("GPG_SECRET"), System.getenv("GPG_SIGNING_PASSWORD"))
        sign(publishing.publications)
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

    val libraryVersion = if (System.getenv("GITHUB_RUN_NUMBER") != null) {
        "$libraryVersionPrefix${System.getenv("GITHUB_RUN_NUMBER")}"
    } else {
        "0.0.00"+System.currentTimeMillis()
    }

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