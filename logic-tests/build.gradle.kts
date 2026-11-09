plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

// Compile the actual app logic, not stale copies. Only platform boundaries have JVM test doubles.
val appLogic = tasks.register<Sync>("syncAppLogic") {
    from("../app/src/main/java") {
        include("com/vyrk/tv/player/PlayerModel.kt", "com/vyrk/tv/player/AudioEffects.kt",
            "com/vyrk/tv/data/ProfileSession.kt", "com/vyrk/tv/data/Models.kt",
            "com/vyrk/tv/data/AtomicFiles.kt", "com/vyrk/tv/data/Diagnostics.kt",
            "com/vyrk/tv/ui/browse/MetaCache.kt")
    }
    into(layout.buildDirectory.dir("generated/appLogic"))
}
kotlin.sourceSets.test { kotlin.srcDir(appLogic.map { it.destinationDir }) }
tasks.named("compileTestKotlin") { dependsOn(appLogic) }
dependencies {
    testImplementation(project(":core"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
tasks.test { useJUnit(); testLogging { events("failed") } }
