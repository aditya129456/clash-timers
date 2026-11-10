import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing is optional and stays OUTSIDE the project (never commit it). Put storeFile,
 * storePassword, keyAlias and keyPassword in ~/.android-signing/clashtimers/keystore.properties,
 * or pass another file with -PclashtimersSigning=<path to keystore.properties>.
 * Without one, assembleRelease builds an unsigned APK. See "Building from source" in the README.
 */
val signingPropsFile: File = (findProperty("clashtimersSigning") as String?)?.let(::File)
    ?: File(System.getProperty("user.home"), ".android-signing/clashtimers/keystore.properties")
val signingProps: Properties? = signingPropsFile.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "com.aditya.clashtimer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.aditya.clashtimer"
        minSdk = 26
        targetSdk = 37
        // Bump both for every release; a lower versionCode can't install over a higher one.
        versionCode = 3
        versionName = "1.0.2"
        // Shown in crash reports (crash/CrashReporter.kt). R8 is off in every build (see release below).
        manifestPlaceholders["r8"] = "off"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Without keystore.properties Gradle builds app-release-unsigned.apk, which phones refuse to install.
            signingConfig = signingConfigs.findByName("release")
            // R8 is OFF on purpose: 1.0.1's R8 build crashed at start-up on Android 10
            // with IllegalAccessError (a merged Kotlin class could no longer reach a package-private
            // one). The app goes to many different phones, so no code shrinking or renaming.
            // Resource shrinking needs R8 code shrinking, so it is off too.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
/**
 * `./gradlew archiveRelease` = assembleRelease, then copies app-release.apk to ClashTimers-<version>.apk
 * in the project root. R8 is off, so there is no mapping file: crash reports are readable as-is.
 */
abstract class ArchiveReleaseTask : DefaultTask() {
    @get:InputFile abstract val apk: RegularFileProperty
    @get:OutputFile abstract val apkOut: RegularFileProperty

    @TaskAction
    fun archive() {
        apk.get().asFile.copyTo(apkOut.get().asFile, overwrite = true)
        logger.lifecycle("APK -> ${apkOut.get().asFile}")
    }
}

val releaseVersion = android.defaultConfig.versionName!!
tasks.register<ArchiveReleaseTask>("archiveRelease") {
    dependsOn("assembleRelease")
    apk.set(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    apkOut.set(rootProject.layout.projectDirectory.file("ClashTimers-$releaseVersion.apk"))
}
