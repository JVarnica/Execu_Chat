
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.execu_chat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.execu_chat"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters.add("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        noCompress += listOf("pte", "json", "model")
    }
    flavorDimensions += "backend"
    productFlavors {
        create("xnnpack") {
            dimension = "backend"
            versionNameSuffix = "-xnnpack"

        }
        create("vulkan") {
            dimension = "backend"
            versionNameSuffix = "-vulkan"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro")
        }
        debug {}
    }
    kotlin {
        jvmToolchain(17)
    }

}
dependencies {
    add("xnnpackImplementation", libs.executorch.xnnpack)
    add("vulkanImplementation", libs.executorch.vulkan)
    implementation(libs.vosk.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.facebook.fbjni)
    implementation(libs.facebook.soloader)
    implementation(libs.facebook.nativeloader)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}