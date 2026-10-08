plugins { kotlin("multiplatform"); id("com.android.kotlin.multiplatform.library"); alias(libs.plugins.maven.publish) }
kotlin {
    jvm()
    android {
        namespace = "com.mocharealm.accompanist.lyrics.phonetics.data.mandarin"
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
// Explicit recursive inputs also track newly added licenses in static asset directories.
tasks.matching { it.name == "mergeAndroidMainAssets" }.configureEach {
    inputs.files(fileTree("packs"), fileTree(layout.buildDirectory.dir("generated/notices")))
        .withPropertyName("phoneticsPackAssets").withPathSensitivity(PathSensitivity.RELATIVE)
}
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Accompanist Phonetics mandarin data")
            description.set("Independently licensed reading data; see bundled notices")
            licenses {
                license { name.set("Apache-2.0 (pack factory code and original additions)"); url.set("https://www.apache.org/licenses/LICENSE-2.0") }
                license { name.set("Unicode-3.0 (data)"); url.set("https://www.unicode.org/license.txt") }
                license { name.set("CedPane public-domain data; Unlicense declaration"); url.set("https://github.com/ssb22/CedPane/blob/bcc2c145da6fa0e03191f0d49ea193f28d924061/LICENSE") }
                license { name.set("MIT (McBopomofo contributions)"); url.set("https://github.com/openvanilla/McBopomofo/blob/be6564acad6c4d3265c34a2e1a872d80f9db6068/LICENSE.txt") }
                license { name.set("BSD-3-Clause (libtabe dictionary; full COPYRIGHT retained)"); url.set("https://github.com/kcwu/libtabe/blob/ea382a829c916f2cceda2022afdf8e8471e6e7b5/tsi-src/COPYING") }
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
