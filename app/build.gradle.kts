// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "com.oculus.camerademo"
  compileSdk = 34
  ndkVersion = "27.2.12479018"

  defaultConfig {
    applicationId = "com.misterslee.alterego.mr"
    minSdk = 34
    targetSdk = 34
    versionCode = 49
    versionName = "1.2.23"
    ndk { abiFilters += "arm64-v8a" }

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
  }

  signingConfigs {
    create("alteregoRelease") {
      storeFile = rootProject.file("signing/alterego-release.p12")
      storePassword = System.getenv("ALTEREGO_SIGN_PASSWORD")
      keyAlias = "alterego"
      keyPassword = System.getenv("ALTEREGO_SIGN_PASSWORD")
      enableV1Signing = true
      enableV2Signing = true
    }
  }
  buildTypes {
    release {
      signingConfig = signingConfigs.getByName("alteregoRelease")
      isDebuggable = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
    freeCompilerArgs += listOf("-Xskip-metadata-version-check")
  }
  buildFeatures { compose = true }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
  implementation(libs.mediapipe.tasks.vision)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.activity)
  implementation(libs.androidx.fragment.ktx)
  val metaSpatialSdkVersion = "0.14.0"
  implementation("com.meta.spatial:meta-spatial-sdk:$metaSpatialSdkVersion")
  implementation("com.meta.spatial:meta-spatial-sdk-toolkit:$metaSpatialSdkVersion")
  implementation("com.meta.spatial:meta-spatial-sdk-vr:$metaSpatialSdkVersion")
  implementation("com.meta.spatial:meta-spatial-sdk-compose:$metaSpatialSdkVersion")
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}
