plugins { kotlin("multiplatform"); id("com.android.kotlin.multiplatform.library"); alias(libs.plugins.maven.publish) }
kotlin {
    jvm()
    android {
        namespace = "com.mocharealm.accompanist.lyrics.phonetics.data.japanese"
        compileSdk = 37
        minSdk = 21
        androidResources { enable = true; noCompress += "lpd" }
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies { api(project(":runtime")) }
        jvmMain { resources.srcDir("packs"); resources.srcDir(layout.buildDirectory.dir("generated/notices")) }
    }
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory("packs")
        variant.sources.assets?.addStaticSourceDirectory("build/generated/notices")
    }
}
tasks.matching { it.name == "mergeAndroidMainAssets" }.configureEach {
    inputs.files(fileTree("packs"), fileTree(layout.buildDirectory.dir("generated/notices")))
        .withPropertyName("phoneticsPackAssets").withPathSensitivity(PathSensitivity.RELATIVE)
}
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Accompanist Phonetics japanese data")
            description.set("Independently licensed reading data; see bundled notices")
            licenses {
                license { name.set("Apache-2.0 (code and original additions)"); url.set("https://www.apache.org/licenses/LICENSE-2.0") }
                license { name.set("NAIST/ICOT (IPADIC dictionary data)"); url.set("https://github.com/taku910/mecab/blob/61b90ba6e669dc2d7d533d4a80d206f3b31d52b1/mecab-ipadic/COPYING") }
            }
        }
    }
}

val packResourcesZip by tasks.registering(Zip::class) {
    archiveClassifier.set("resources")
    from("packs", layout.buildDirectory.dir("generated/notices"))
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
publishing.publications.withType<MavenPublication>().configureEach {
    if (name == "kotlinMultiplatform" || name.startsWith("ios")) artifact(packResourcesZip)
}
