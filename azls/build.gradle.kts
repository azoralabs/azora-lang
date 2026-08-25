plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.compiler) {
        // The JVM LLVM bindings (native toolchains for every OS/arch, ~5 GB)
        // are only needed to produce machine code — never for language
        // intelligence. Excluding them keeps azls.jar a few megabytes.
        exclude(group = "org.bytedeco")
    }
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

/**
 * Self-contained language-server process: AZLS + compiler + runtime deps.
 * Editors launch this jar with `--stdio` and communicate through standard
 * JSON-RPC 2.0 / Language Server Protocol framing.
 */
val fatJar = tasks.register<Jar>("fatJar") {
    group = "distribution"
    archiveBaseName.set("azls")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Implementation-Title"] = "Azora Language Server"
        // The process entry point owns stdout exclusively for LSP framing.
        attributes["Main-Class"] = "org.azora.azls.AzlsStdio"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
    }
}

/** Installs the language server where Azora Studio auto-discovers it. */
tasks.register<Copy>("installAzls") {
    group = "distribution"
    dependsOn(fatJar)
    from(fatJar.map { it.archiveFile })
    rename { "azls.jar" }
    into(File(System.getProperty("user.home"), ".azora/azls"))
}
