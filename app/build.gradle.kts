plugins { id("com.android.application") version "9.1.1" }
android {
    namespace = "com.personality.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.personality.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    testImplementation("junit:junit:4.13.2")
}
