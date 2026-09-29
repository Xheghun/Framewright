plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xheghun.framewright.docs"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":analytics"))
    implementation(project(":bandwidth-monitor"))
    implementation(project(":codec-inspector"))
    implementation(project(":diagnostics-overlay"))
    implementation(project(":drm-inspector"))
    implementation(project(":media3-adapter"))
    implementation(project(":storage"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.media3.exoplayer)
}
