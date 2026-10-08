import java.security.MessageDigest

plugins { id("com.android.application") }
val runtimeDigest = MessageDigest.getInstance("SHA-256").apply {
    rootProject.fileTree("runtime/src").matching { include("**/*.kt"); exclude("**/commonTest/**") }.files.sortedBy { it.path }.forEach { update(it.readBytes()) }
}.digest().joinToString("") { "%02x".format(it) }
android {
    namespace = "com.mocharealm.accompanist.lyrics.phonetics.devicebenchmark"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mocharealm.accompanist.phonetics.benchmark"; minSdk = 21; targetSdk = 37; versionCode = 1; versionName = project.version.toString()
        buildConfigField("String", "RUNTIME_SOURCES_SHA256", "\"$runtimeDigest\"")
    }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += "lpd" }
    sourceSets.getByName("main").assets.srcDir("../benchmark/src/main/resources")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    implementation(project(":runtime"))
    implementation(project(":data-mandarin"))
    implementation(project(":data-cantonese"))
    implementation(project(":data-japanese"))
}
