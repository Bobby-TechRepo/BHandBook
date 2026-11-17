import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing: copy keystore.properties.template to keystore.properties (git-ignored) and fill it in. Without it, a release build is simply unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { stream -> load(stream) }
}

android {
    namespace = "com.bsoft.bhandbook"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bsoft.bhandbook"
        minSdk = 26                      // spec: Android 8.0
        targetSdk = 37
        // one place to change a version: gradle.properties (or -PappVersionName=1.0.1 -PappVersionCode=2 on the command line)
        versionCode = providers.gradleProperty("appVersionCode").map { it.toInt() }.getOrElse(1)
        versionName = providers.gradleProperty("appVersionName").getOrElse("1.0.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    fun signingValue(property: String, environment: String): String? = keystoreProps.getProperty(property) ?: System.getenv(environment)

    val releaseStoreFile = signingValue("storeFile", "BHANDBOOK_KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "BHANDBOOK_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "BHANDBOOK_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "BHANDBOOK_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            // ONNX Runtime calls back into Java by name from native code, so its classes must survive shrinking (see app/proguard-rules.pro).
            // If Gradle sync rejects proguardFiles under AGP 9's optimization DSL, see docs/RELEASE.md ("If the build rejects the keep rules").
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ONNX Runtime ships native libraries for four CPU types; phones are arm64 / arm32, and dropping x86 saves about half of its size.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            signingConfig = if (releaseStoreFile != null) signingConfigs.getByName("release") else null
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
        unitTests.isReturnDefaultValues = true
    }
    androidResources {
        noCompress += listOf("onnx")        // keep model files uncompressed in the APK (faster, mappable loading)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.onnxruntime.android)   // full build: the INT8 models need ConvInteger / MatMulInteger, which the reduced "mobile" package may omit
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
