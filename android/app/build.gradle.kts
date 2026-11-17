plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.agenda.app"
    compileSdk = 36
    defaultConfig {
        applicationId = if(providers.gradleProperty("agendaPreview").orNull=="true") "com.agenda.local.preview" else "com.agenda.local"
        manifestPlaceholders["appLabel"] = if(providers.gradleProperty("agendaPreview").orNull=="true") "Agenda · 新版预览" else "Agenda"
        minSdk = 31
        targetSdk = 36
        versionCode = 24
        versionName = "0.14.4-uniform-connectors"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    testImplementation(libs.junit)
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.viewmodel)
    debugImplementation(libs.compose.tooling)
}
