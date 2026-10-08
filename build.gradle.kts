import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.maven.publish) apply false
}
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0")

allprojects {
    group = "com.mocharealm.accompanist"
    version = rootProject.version
}
tasks.register<Delete>("clean") {
    dependsOn(subprojects.map { "${it.path}:clean" })
    delete(layout.buildDirectory)
}
val generateReleaseNotices by tasks.registering(Exec::class) {
    workingDir(rootDir)
    inputs.files("tools/data.py", "LICENSE", "NOTICE", "data/sources.lock.json", "data/compiled-packs.tsv", "data/curated/sources.json")
    inputs.dir("data/licenses")
    inputs.file("data-japanese/src/commonMain/kotlin/com/mocharealm/accompanist/lyrics/phonetics/data/IpadicLexicon.kt")
    listOf("runtime", "data-mandarin", "data-cantonese", "data-japanese").forEach {
        outputs.dir(layout.projectDirectory.dir("$it/build/generated/notices"))
    }
    val interpreter = providers.gradleProperty("pythonCommand").getOrElse("python")
    if (System.getProperty("os.name").startsWith("Windows")) commandLine("cmd", "/c", interpreter, "tools/data.py", "notices")
    else commandLine(interpreter, "tools/data.py", "notices")
}
val verifyReleaseData by tasks.registering(Exec::class) {
    dependsOn(generateReleaseNotices)
    workingDir(rootDir)
    val interpreter = providers.gradleProperty("pythonCommand").getOrElse("python")
    if (System.getProperty("os.name").startsWith("Windows")) commandLine("cmd", "/c", interpreter, "tools/data.py", "verify")
    else commandLine(interpreter, "tools/data.py", "verify")
}
tasks.register("check") {
    dependsOn(verifyReleaseData, "testReadingConversion", ":runtime:jvmTest", ":runtime:testAndroidHostTest", ":data-compiler:test", ":benchmark:test")
}
tasks.register<Exec>("testReadingConversion") {
    workingDir(rootDir)
    val interpreter = providers.gradleProperty("pythonCommand").getOrElse("python")
    if (System.getProperty("os.name").startsWith("Windows")) commandLine("cmd", "/c", interpreter, "-m", "unittest", "discover", "-s", "data-compiler/src/test/python", "-p", "test_*.py")
    else commandLine(interpreter, "-m", "unittest", "discover", "-s", "data-compiler/src/test/python", "-p", "test_*.py")
}
subprojects {
    tasks.matching {
        it.name.endsWith("ProcessResources") || it.name.startsWith("process") && it.name.endsWith("JavaRes") ||
            it.name == "mergeAndroidMainAssets"
    }.configureEach { dependsOn(generateReleaseNotices) }
    tasks.matching { it.name == "assemble" || it.name.startsWith("publish") || it.name == "packResourcesZip" }.configureEach {
        dependsOn(verifyReleaseData)
    }
    tasks.withType<Jar>().configureEach {
        if (project.name in listOf("runtime", "data-mandarin", "data-cantonese", "data-japanese")) {
            dependsOn(generateReleaseNotices)
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(listOf(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("META-INF") }
        from(project.layout.buildDirectory.dir("generated/notices"))
    }
    tasks.withType<Zip>().configureEach {
        if (name.endsWith("Klib")) {
            dependsOn(generateReleaseNotices)
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            from(listOf(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("default/resources/META-INF") }
            from(project.layout.buildDirectory.dir("generated/notices")) { into("default/resources") }
        }
    }
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            coordinates(group.toString(), "lyrics-phonetics-${project.name}", version.toString())
            publishToMavenCentral(automaticRelease = true)
            if (!providers.gradleProperty("ciPackaging").map(String::toBoolean).getOrElse(false) &&
                !providers.gradleProperty("localUnsigned").map(String::toBoolean).getOrElse(false)) {
                signAllPublications()
            }
            pom {
                url.set("https://github.com/6xingyv/accompanist-lyrics-phonetics")
                inceptionYear.set("2026")
                developers {
                    developer {
                        id.set("6xingyv")
                        name.set("Simon Scholz")
                        url.set("https://github.com/6xingyv")
                    }
                }
                scm {
                    url.set("https://github.com/6xingyv/accompanist-lyrics-phonetics")
                    connection.set("scm:git:git://github.com/6xingyv/accompanist-lyrics-phonetics.git")
                    developerConnection.set("scm:git:ssh://git@github.com/6xingyv/accompanist-lyrics-phonetics.git")
                }
            }
        }
    }
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven { name = "local"; url = uri(providers.gradleProperty("localMavenRepository").getOrElse("file:///E:/maven")) }
                maven { name = "ci"; url = rootProject.layout.buildDirectory.dir("ci-maven").get().asFile.toURI() }
            }
        }
    }
}
tasks.register<Sync>("exportIosResources") {
    from("data-mandarin/packs", "data-cantonese/packs", "data-japanese/packs")
    dependsOn(generateReleaseNotices)
    from("data-mandarin/build/generated/notices", "data-cantonese/build/generated/notices", "data-japanese/build/generated/notices")
    into(layout.buildDirectory.dir("ios-resources"))
}
