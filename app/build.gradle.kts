import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun prop(name: String, default: String = ""): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotEmpty() } ?: default

val appName = prop("vyrk.appName", "VYRK")
val appId = prop("vyrk.applicationId", "com.vyrk.tv")
val verName = prop("vyrk.versionName", "0.1.0")
val verCode = prop("vyrk.versionCode", "1").toInt()
val updateRepo = prop("vyrk.updateRepo")
val top10Url = prop("vyrk.top10AddonUrl")
val storeBuild = prop("vyrk.storeBuild", "false").toBoolean()

// Release signing comes from the environment. Without it, builds are signed with the committed
// debug key in ci/debug-signing/, which is NOT secret and only for testing.
val envKeystore = System.getenv("VYRK_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "com.vyrk.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 23
        targetSdk = 36
        versionCode = verCode
        versionName = verName
        resValue("string", "app_name", appName)
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "TOP10_ADDON_URL", "\"$top10Url\"")
        buildConfigField("boolean", "STORE_BUILD", storeBuild.toString())
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (envKeystore != null) {
                storeFile = file(envKeystore)
                storePassword = System.getenv("VYRK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VYRK_KEY_ALIAS")
                keyPassword = System.getenv("VYRK_KEY_PASSWORD")
            } else {
                val p = Properties().apply { rootProject.file("ci/debug-signing/debug-signing.properties").inputStream().use { load(it) } }
                storeFile = rootProject.file("ci/debug-signing/" + p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
        getByName("debug") {
            val p = Properties().apply { rootProject.file("ci/debug-signing/debug-signing.properties").inputStream().use { load(it) } }
            storeFile = rootProject.file("ci/debug-signing/" + p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    // Store builds overlay a manifest that removes REQUEST_INSTALL_PACKAGES.
    if (storeBuild) {
        sourceSets.getByName("release").manifest.srcFile("src/store/AndroidManifest.xml")
        sourceSets.getByName("debug").manifest.srcFile("src/store/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}


kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Per-ABI version codes so every split can be installed over the previous release.
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 4)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val base = abiCodes[abi] ?: 0
            output.versionCode.set(verCode * 10 + base)
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.tvprovider)
    implementation(libs.zxing.core)
    implementation(libs.youtube.player)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
