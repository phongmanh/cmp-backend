// Versions come from the root project, which already put the Kotlin and ktlint plugins on the
// build classpath. Repeating them through a catalog alias here makes Gradle refuse the build.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
    `maven-publish`
}

group = "com.example"
version = rootProject.version

ktlint {
    version = "1.5.0"
}

kotlin {
    jvmToolchain(21)

    // The JVM artifact is what both the Ktor server and an Android consumer resolve; Kotlin
    // Multiplatform maps `androidTarget` onto a plain JVM library, so no Android Gradle plugin has
    // to be dragged into this backend build.
    jvm()

    // Node, not `browser()`. The published klib is byte-identical either way and no variant
    // attribute records the choice, so a browser consumer loses nothing; what `browser()` adds is a
    // Karma test task that defaults to ChromeHeadless, so `./gradlew build` would then need Chrome
    // installed on every machine that runs it, and it pulls Karma and webpack into the yarn lock.
    js {
        nodejs()
    }

    wasmJs {
        nodejs()
    }

    // Device and Apple Silicon simulator. A host that cannot build a given native target — a Linux
    // CI runner, for instance — has its compile tasks disabled by the Kotlin plugin rather than
    // failing the build, so the backend still builds and tests everywhere.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: every consumer deserializes these types, so the
            // serialization runtime is part of this module's surface.
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// `./gradlew :shared:publishToMavenLocal` is enough for a mobile build on the same machine. Point
// this at the real repository once the contract is consumed off this box.
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = "api-contract${artifactId.removePrefix("shared")}"
    }
}
