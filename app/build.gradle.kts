import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.android")
}

// Load local.properties so secrets stay out of source control
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.dialingassistant.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dialingassistant.agent"
        minSdk = 26
        //noinspection EditedTargetSdkVersion
        targetSdk = 35
        versionCode = 6
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Injected from local.properties — used by CallReceiver to POST push notifications
        // even when the web app is closed.
        buildConfigField("String", "WEB_APP_URL",
            "\"${localProps.getProperty("WEB_APP_URL", "https://your-agent-web-domain.com")}\"")
        buildConfigField("String", "NOTIFICATION_SECRET",
            "\"${localProps.getProperty("NOTIFICATION_SECRET", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../ninja-connect.keystore")
            storePassword = "ninja@connect123"
            keyAlias = "ninja-connect"
            keyPassword = "ninja@connect123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.15.0")

    // Firebase (BoM keeps all versions aligned)
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")

    // Lottie animations
    implementation("com.airbnb.android:lottie:6.3.0")

    // Local network server (LAN fallback when internet is unavailable)
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
