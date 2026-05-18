plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Read version from BuildConfig source of truth
val azoraVersion: String = file("build-config/src/commonMain/kotlin/dev/azora/lang/BuildConfig.kt")
    .readLines()
    .first { it.contains("const val VERSION") }
    .let { Regex(""""([^"]+)"""").find(it)!!.groupValues[1] }

// --- Distribution tasks ---

// Pre-create VERSION staging file at configuration time (plain string, no project refs in actions)
val versionStagingDir = File(layout.buildDirectory.asFile.get(), "dist-staging")
versionStagingDir.mkdirs()
File(versionStagingDir, "VERSION").writeText(azoraVersion)

val assembleDist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles the Azora CLI distribution into build/dist/azora/"

    dependsOn(":app:installDist", ":build-tool:installDist")

    into(layout.buildDirectory.dir("dist/azora"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // App distribution (azora scripts + all dependency JARs)
    from("app/build/install/azora") {
        include("bin/**", "lib/**")
        // Exclude Gradle's default project-name scripts (we use applicationName)
        exclude("bin/app", "bin/app.bat")
    }

    // Build-tool scripts
    from("build-tool/build/install/azora-build/bin") {
        into("bin")
        exclude("build-tool", "build-tool.bat")
    }

    // Build-tool JARs (duplicates with app are skipped)
    from("build-tool/build/install/azora-build/lib") {
        into("lib")
    }

    // Standard library and engine modules
    from("Internal") {
        into("Internal")
        exclude("**/node_modules", "**/dist", "**/.DS_Store")
    }

    // Install scripts
    from(projectDir) {
        include("install.sh", "install.ps1", "install.bat")
    }

    // VERSION file
    from(versionStagingDir) {
        include("VERSION")
    }
}

tasks.register<Zip>("distZip") {
    group = "distribution"
    description = "Creates a ZIP archive of the Azora distribution"
    dependsOn(assembleDist)

    archiveBaseName.set("azora")
    archiveVersion.set(azoraVersion)
    destinationDirectory.set(layout.buildDirectory.dir("dist"))

    from(layout.buildDirectory.dir("dist/azora")) {
        into("azora-$azoraVersion")
    }
}

tasks.register<Tar>("distTar") {
    group = "distribution"
    description = "Creates a tar.gz archive of the Azora distribution"
    dependsOn(assembleDist)

    archiveBaseName.set("azora")
    archiveVersion.set(azoraVersion)
    compression = Compression.GZIP
    destinationDirectory.set(layout.buildDirectory.dir("dist"))

    from(layout.buildDirectory.dir("dist/azora")) {
        into("azora-$azoraVersion")
    }
}
