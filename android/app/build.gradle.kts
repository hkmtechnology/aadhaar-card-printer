plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.hkmtech.cardprinter"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.hkmtech.cardprinter"
    minSdk = 24
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
  }

  signingConfigs {
    create("release") {
      storeFile = file("../release.keystore")
      storePassword = "hkmcardprinter"
      keyAlias = "hkmcardprinter"
      keyPassword = "hkmcardprinter"
      enableV1Signing = true
      enableV2Signing = true
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Google ML Kit + OpenCV for Precision Card Detection, Face Studio AI & Background Removal
  implementation("com.google.mlkit:object-detection:17.0.2")
  implementation("com.google.mlkit:text-recognition:16.0.1")
  implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
  implementation("com.google.mlkit:face-detection:16.1.7")
  implementation("org.opencv:opencv:4.11.0")
}
