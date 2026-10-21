plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ── Versioning ────────────────────────────────────────────────────────────────
// versionCode : GITHUB_RUN_NUMBER (auto-incrementing integer); local fallback = 1
// versionName : tag build → "1.2.3" (v-prefix stripped)
//               CI non-tag → "0.0.0-<sha7>"
//               local      → "0.0.0-<gitSha7>"
fun gitShortSha(): String = try {
    ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().readLine()?.trim() ?: "unknown"
} catch (_: Exception) { "unknown" }

val ciRunNumber   = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val ciRefType     = System.getenv("GITHUB_REF_TYPE")          // "tag" | "branch" | null
val ciRefName     = System.getenv("GITHUB_REF_NAME")          // "v1.2.3" | branch name | null
val ciSha         = System.getenv("GITHUB_SHA")?.take(7)

val appVersionCode = ciRunNumber ?: 1

val appVersionName = when {
    ciRefType == "tag" && ciRefName != null -> ciRefName.removePrefix("v")
    ciRunNumber != null                     -> "0.0.0-${ciSha ?: gitShortSha()}"
    else                                    -> "0.0.0-${gitShortSha()}"
}
// ─────────────────────────────────────────────────────────────────────────────

android {
    namespace = "com.frodrigues.obdmqtt"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.frodrigues.obdmqtt"
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val envKeystorePath  = System.getenv("SIGNING_KEYSTORE_PATH")
    val envKeyAlias      = System.getenv("SIGNING_KEY_ALIAS")
    val envKeyPassword   = System.getenv("SIGNING_KEY_PASSWORD")
    val envStorePassword = System.getenv("SIGNING_STORE_PASSWORD")

    signingConfigs {
        if (envKeystorePath != null) {
            create("release") {
                storeFile     = file(envKeystorePath)
                storePassword = envStorePassword
                keyAlias      = envKeyAlias
                keyPassword   = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (envKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
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
    testOptions {
        // android.util.Log calls in OBD classes return defaults instead of throwing in JVM tests
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}