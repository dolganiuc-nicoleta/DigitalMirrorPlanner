import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// cheia OpenRouteService stă doar în local.properties (care nu ajunge în Git)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val orsApiKey: String = localProperties.getProperty("ORS_API_KEY", "").trim()
// adresa serverului pentru copia în cloud; goală = butoanele de cloud nu apar în aplicație
val cloudUrl: String = localProperties.getProperty("CLOUD_URL", "").trim().trimEnd('/')

android {
    namespace = "md.utm.digitalmirror"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "md.utm.digitalmirror"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // disponibilă în cod ca BuildConfig.ORS_API_KEY
        buildConfigField("String", "ORS_API_KEY", "\"$orsApiKey\"")
        // disponibilă în cod ca BuildConfig.CLOUD_URL
        buildConfigField("String", "CLOUD_URL", "\"$cloudUrl\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))     // logica comună (estimare, planificare, mese, drumuri, somn)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.health.connect:connect-client:1.1.0")
    // widgetul de pe ecranul principal, scris în stil Compose
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.osmdroid.android)
    implementation(libs.play.services.location)
}