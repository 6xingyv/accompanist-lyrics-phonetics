import java.security.MessageDigest

plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(21) }
dependencies { implementation(project(":runtime")); implementation(project(":data-mandarin")); implementation(project(":data-cantonese")); implementation(project(":data-japanese")); testImplementation(kotlin("test")) }
application { mainClass.set("com.mocharealm.accompanist.lyrics.phonetics.benchmark.MainKt") }
val evaluationStamp by tasks.registering {
    inputs.property("version", project.version.toString())
    val sources = rootProject.fileTree("runtime/src") { include("commonMain/**/*.kt", "jvmMain/**/*.kt") } +
        rootProject.fileTree("data-japanese/src/commonMain") { include("**/*.kt") }
    inputs.files(sources)
    val target = layout.buildDirectory.file("generated/evaluation/evaluation.properties")
    outputs.file(target)
    doLast {
        val hash = MessageDigest.getInstance("SHA-256")
        sources.files.sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }.forEach {
            hash.update(it.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
            hash.update(it.readBytes())
        }
        target.get().asFile.apply { parentFile.mkdirs(); writeText("libraryVersion=${project.version}\nstrategySourcesSha256=${hash.digest().joinToString("") { "%02x".format(it) }}\n") }
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/evaluation")) }
tasks.processResources { dependsOn(evaluationStamp) }
