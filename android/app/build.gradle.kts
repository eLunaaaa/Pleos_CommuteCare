/**
 * @file build.gradle.kts
 * @brief Pleos SDK 및 Android 빌드를 구성하고 배포용 APK에서 로컬 키를 제외할 옵션을 제공한다.
 */
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.pleos.commutecare"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.pleos.commutecare"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Local Secret Properties Loading (.env / local.properties)
        // Git에 포함할 APK는 -PexcludeLocalSecrets=true로 빌드해 로컬 키를 제외한다.
        val excludeLocalSecrets = providers.gradleProperty("excludeLocalSecrets").orNull == "true"
        val envFile = rootProject.file("../.env")
        val envProperties = Properties()
        if (!excludeLocalSecrets && envFile.exists()) {
            envFile.inputStream().use { envProperties.load(it) }
        }

        val openAiApiKey = if (excludeLocalSecrets) "" else envProperties.getProperty("OPENAI_API_KEY")
            ?: System.getenv("OPENAI_API_KEY") ?: ""
        val kmaServiceKey = if (excludeLocalSecrets) "" else envProperties.getProperty("KMA_SERVICE_KEY")
            ?: System.getenv("KMA_SERVICE_KEY") ?: ""

        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        buildConfigField("String", "KMA_SERVICE_KEY", "\"$kmaServiceKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Pleos Playground SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
