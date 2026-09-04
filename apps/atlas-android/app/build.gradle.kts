plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.grinningfrog.atlas"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.grinningfrog.atlas"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.0-alpha.2"

        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "ATLAS_GATEWAY_URL", "\"${providers.gradleProperty("ATLAS_GATEWAY_URL").orElse("").get()}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${providers.gradleProperty("SUPABASE_URL").orElse("").get()}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${providers.gradleProperty("SUPABASE_PUBLISHABLE_KEY").orElse("").get()}\"")
    }

    flavorDimensions += "transport"
    productFlavors {
        create("secure") {
            dimension = "transport"
            manifestPlaceholders["atlasCleartext"] = "false"
            manifestPlaceholders["atlasAppLabel"] = "Atlas"
        }
        create("lan") {
            dimension = "transport"
            applicationIdSuffix = ".lan"
            versionNameSuffix = "-lan"
            manifestPlaceholders["atlasCleartext"] = "true"
            manifestPlaceholders["atlasAppLabel"] = "Atlas LAN"
        }
    }

    signingConfigs {
        val keystorePath = providers.environmentVariable("ATLAS_ANDROID_KEYSTORE").orNull
        if (!keystorePath.isNullOrBlank()) create("alpha") {
            storeFile = file(keystorePath)
            storePassword = providers.environmentVariable("ATLAS_ANDROID_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("ATLAS_ANDROID_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("ATLAS_ANDROID_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("alpha")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    buildFeatures { compose = true; buildConfig = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20250517")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
