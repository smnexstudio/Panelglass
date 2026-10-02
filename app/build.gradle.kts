plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.smnexstudio.panelglass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smnexstudio.panelglass"
        minSdk = 31
        targetSdk = 35
        // Release builds pass the version from the git tag (-PversionName=1.2.3); versionCode is derived from it so
        // every release installs over the previous one. Local builds keep the defaults.
        val appVersion = (findProperty("versionName") as String?) ?: "0.1.0"
        versionName = appVersion
        versionCode = appVersion.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .let { (it + listOf(0, 0, 0)).take(3) }.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
            .coerceAtLeast(1)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from the environment only (the release workflow's secrets). Nothing about the key is
    // ever in the repository; without these variables the release APK is built unsigned.
    val releaseKeystore = System.getenv("PANELGLASS_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("PANELGLASS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PANELGLASS_KEY_ALIAS")
            keyPassword = System.getenv("PANELGLASS_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                this.outputFileName = "panelglass-${versionName}-${name}.apk"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Studio fonts are mapped straight from the APK by Font.Builder(assets, …), so they are stored uncompressed.
    androidResources { noCompress += listOf("ttf", "otf") }
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:ocr"))
    implementation(project(":core:render"))
    implementation(project(":core:engine"))
    implementation(project(":core:pipeline"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:library"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:studio"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
