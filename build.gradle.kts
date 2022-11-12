import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    id("dev.petuska.npm.publish") version "2.1.2"
    kotlin("multiplatform") version "1.7.21"
    id("com.android.library")
    id("io.codearte.nexus-staging") version "0.30.0"
    `maven-publish`
    signing
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0"
    id("org.jlleitschuh.gradle.ktlint-idea") version "11.0.0"
}

val libraryVersionPrefix: String by project
group = "com.ditchoom"
version = "$libraryVersionPrefix.0-SNAPSHOT"
val libraryVersion = if (System.getenv("GITHUB_RUN_NUMBER") != null) {
    "$libraryVersionPrefix${(Integer.parseInt(System.getenv("GITHUB_RUN_NUMBER")) + 60)}"
} else {
    "${libraryVersionPrefix}0-SNAPSHOT"
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    android {
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
    js {
        moduleName = "buffer-kt"
        browser()
        nodejs()
    }

    macosX64()
    linuxX64()
    ios()
    iosSimulatorArm64()
    tasks.getByName<KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
        deviceId = "iPhone 14"
    }
    watchos()
    tvos()
    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
            }
        }
        val jvmMain by getting {
        }
        val jvmTest by getting {
            kotlin.srcDir("src/commonJvmTest/kotlin")
        }
        val jsMain by getting
        val jsTest by getting
        val macosX64Main by getting
        val macosX64Test by getting
        val linuxX64Main by getting
        val linuxX64Test by getting
        val iosMain by getting
        val iosTest by getting
        val iosSimulatorArm64Main by getting
        val iosSimulatorArm64Test by getting
        val watchosMain by getting
        val watchosTest by getting
        val tvosMain by getting
        val tvosTest by getting

        val nativeMain by sourceSets.creating {
            dependsOn(commonMain)
            macosX64Main.dependsOn(this)
            linuxX64Main.dependsOn(this)
            iosMain.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            watchosMain.dependsOn(this)
            tvosMain.dependsOn(this)
        }
        val nativeTest by sourceSets.creating {
            dependsOn(commonTest)
            macosX64Test.dependsOn(this)
            linuxX64Test.dependsOn(this)
            iosTest.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
            watchosTest.dependsOn(this)
            tvosTest.dependsOn(this)
        }

        val androidMain by getting
        val androidTest by getting {
            kotlin.srcDir("src/commonJvmTest/kotlin")
        }
        val androidAndroidTest by getting {
            dependsOn(commonTest)
            kotlin.srcDir("src/commonJvmTest/kotlin")
            kotlin.srcDir("src/commonTest/kotlin")
        }
    }
}

android {
    compileSdk = 33
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk =  9
        targetSdk = 33
    }
    namespace = "$group.${rootProject.name}"
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

(System.getenv("GITHUB_REPOSITORY"))?.let {
    if (System.getenv("GITHUB_REF") == "refs/heads/main") {
        signing {
            useInMemoryPgpKeys("56F1A973", System.getenv("GPG_SECRET"), System.getenv("GPG_SIGNING_PASSWORD"))
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

if (System.getenv("NPM_ACCESS_TOKEN") != null) {
    npmPublishing {
        repositories {
            repository("npmjs") {
                registry = uri("https://registry.npmjs.org")
                authToken = System.getenv("NPM_ACCESS_TOKEN")
            }
        }
        readme = file("Readme.md")
        organization = "ditchoom"
        access = PUBLIC
        bundleKotlinDependencies = true
        version = libraryVersion
        dry = !"refs/heads/main".equals(System.getenv("GITHUB_REF"), ignoreCase = true)
        publications {
            val js by getting {
                moduleName = "buffer-kt"
            }
        }
    }
}
ktlint {
    verbose.set(true)
    outputToConsole.set(true)
}
