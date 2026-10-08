plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(21) }
dependencies { implementation(project(":runtime")); testImplementation(kotlin("test")) }
application { mainClass.set("com.mocharealm.accompanist.lyrics.phonetics.compiler.MainKt") }
