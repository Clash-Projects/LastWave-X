plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.clashprojects.lastwave"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clashprojects.lastwave"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-data"))
    implementation(project(":innertube"))
    implementation(project(":audiodsp"))
    implementation(project(":player"))
    implementation(project(":lyrics"))
    implementation(project(":integrations"))
    implementation(project(":addons"))
}
