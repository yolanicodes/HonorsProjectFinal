import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.honoursproject"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.honoursproject"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
        compilerOptions{
            jvmTarget = JvmTarget.fromTarget("1.8")
        }
    }
    buildFeatures {
        compose = true
    }
//    composeOptions {
//        kotlinCompilerExtensionVersion = "1.5.1"
//    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Standard Wear OS libraries
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.watchface)

    // Compose for Wear OS
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.tooling.preview)

    // Splash Screen API
    implementation(libs.androidx.core.splashscreen)

    // Modern Tiles & Protolayout Libraries
    // This is the main library for creating Tile Services.
    implementation(libs.androidx.tiles)
    // This is for building the UI layouts inside the tile.
    implementation(libs.androidx.protolayout)
    implementation(libs.androidx.tiles.tooling.preview)
    implementation(libs.firebase.crashlytics.buildtools)
    implementation(libs.androidx.navigation.compose.android)
    implementation(libs.androidx.core.ktx)
    //implementation(libs.androidx.navigation.compose.jvmstubs)
    // This is for previewing tiles in Android Studio.
    debugImplementation(libs.androidx.tiles.tooling)

    // Horologist (for SuspendingTileService)
    // The 'exclude' rule is kept to prevent any potential transitive conflicts.
    implementation(libs.horologist.tiles)
    implementation(libs.horologist.compose.tools)

    // Other project dependencies
    implementation("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation(libs.androidx.datastore.preferences)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Test dependencies
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}