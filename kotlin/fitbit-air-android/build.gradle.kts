// Plain Android (no Flutter) build of the same transport the Flutter plugin uses.
// It compiles the shared sources in ../android/src/main/kotlin, except the
// Flutter bridge (FitbitAirPlugin.kt), plus the Kotlin client API here.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "dev.airpulse"
version = "0.1.0"

val shared = rootProject.file("../android/src/main")

android {
    namespace = "dev.airpulse.fitbit_air"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles(file("../../android/consumer-rules.pro"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs(layout.buildDirectory.dir("shared/kotlin"), "src/main/kotlin")
            manifest.srcFile(File(shared, "AndroidManifest.xml"))
            jniLibs.srcDirs(File(shared, "jniLibs"))
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Copies the shared transport sources without the class that needs the
// Flutter embedding. Flutter requires that class to stay in src/main.
val syncSharedSources by tasks.registering(Sync::class) {
    from(File(shared, "kotlin")) { exclude("**/FitbitAirPlugin.kt") }
    into(layout.buildDirectory.dir("shared/kotlin"))
}
tasks.named("preBuild") { dependsOn(syncSharedSources) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.jakewharton.timber:timber:4.7.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "fitbit-air-android"
            afterEvaluate { from(components["release"]) }
        }
    }
}
