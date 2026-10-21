plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.grinningfrog.atlas"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    defaultConfig {
        applicationId = "com.grinningfrog.atlas"
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
        versionCode = providers.environmentVariable("ATLAS_ANDROID_VERSION_CODE").orElse("31").get().toInt().also {
            require(it in 1..2_100_000_000) { "ATLAS_ANDROID_VERSION_CODE is outside Android limits" }
        }
        versionName = "0.1.0-alpha.30"

        manifestPlaceholders["atlasDevSuffix"] = ""
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

    if (providers.environmentVariable("ATLAS_REQUIRE_STABLE_SIGNING").orNull == "true") {
        require(signingConfigs.findByName("alpha") != null) { "Installable CI builds require the persistent alpha signing key" }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("alpha") ?: signingConfigs.getByName("debug")
        }
        create("developer") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["atlasDevSuffix"] = " DEV"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("alpha")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("debug").java.srcDir("src/standard/java")
        getByName("release").java.srcDir("src/standard/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            it.jvmArgs(
                "-Djava.library.path=${layout.buildDirectory.dir("workspace-native-host").get().asFile.absolutePath}",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
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

    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add("developerImplementation", "androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20250517")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
