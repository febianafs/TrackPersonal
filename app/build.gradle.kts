plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.trackpersonal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.trackpersonal"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        viewBinding = true
        buildConfig = true
    }
    // ⬇️ Fix konflik META-INF dari Netty/HiveMQ
    packaging {
        resources {
            // pilih salah satu file META-INF saat ada duplikasi (lebih ringkas daripada daftarin satu-satu)
            pickFirsts += setOf(
                "META-INF/*",
                "META-INF/**"
            )
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.hivemq:hivemq-mqtt-client:1.3.3")
    implementation ("com.google.code.gson:gson:2.10.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    // AndroidX Security
    implementation("androidx.security:security-crypto:1.1.0-alpha03")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // Glide (untuk load logo & avatar)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // --- Netty BOM: kunci versi semua modul Netty biar konsisten ---
    implementation(platform("io.netty:netty-bom:4.1.111.Final"))
    // --- Modul yang dibutuhin WebSocket HiveMQ (HTTP + handler) ---
    implementation("io.netty:netty-codec-http")
    implementation("io.netty:netty-handler")
    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}