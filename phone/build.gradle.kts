plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "kz.crdrive.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "kz.crdrive"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        val telemetryUrl = System.getenv("CR_DRIVE_TELEMETRY_URL") ?: ""
        val deviceToken = System.getenv("CR_DRIVE_DEVICE_TOKEN") ?: ""
        val activationUrl = System.getenv("CR_DRIVE_ACTIVATION_URL") ?: telemetryUrl.replace("ingest-telemetry", "activate-driver")
        buildConfigField("String", "TELEMETRY_URL", "\"${telemetryUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "DEVICE_TOKEN", "\"${deviceToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "ACTIVATION_URL", "\"${activationUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
