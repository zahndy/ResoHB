plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zahndy.resohb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zahndy.resohbs"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "1.4"
        setProperty("archivesBaseName", "ResoHBS_$versionName")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    implementation ("androidx.fragment:fragment:1.8.6")
    implementation ("androidx.fragment:fragment-ktx:1.8.6")
    // Health Services for heart rate
    implementation("androidx.health:health-services-client:1.1.0-alpha05")
    // WebSocket client (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // For background processing
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // For coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    implementation(libs.play.services.wearable)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material)
    implementation(libs.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.material3)
    implementation(libs.androidx.ui)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}