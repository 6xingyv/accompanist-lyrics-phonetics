plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.maven.publish)
}
kotlin {
    jvm()
    android {
        namespace = "com.mocharealm.accompanist.lyrics.phonetics"; compileSdk = 37; minSdk = 21
        androidResources { enable = true }
        withHostTestBuilder {}.configure {}
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmMain { resources.srcDir(layout.buildDirectory.dir("generated/notices")) }
        androidMain { resources.srcDir(layout.buildDirectory.dir("generated/notices")) }
    }
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addStaticSourceDirectory("build/generated/notices") }
}
tasks.matching { it.name == "mergeAndroidMainAssets" }.configureEach {
    inputs.files(fileTree(layout.buildDirectory.dir("generated/notices")))
        .withPropertyName("phoneticsCodeNotices").withPathSensitivity(PathSensitivity.RELATIVE)
}
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Accompanist Lyrics Phonetics")
            description.set("On-device context-aware reading resolution for lyrics")
            licenses { license { name.set("Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
        }
    }
}
