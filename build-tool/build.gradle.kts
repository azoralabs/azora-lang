plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("dev.azora.build.MainKt")
    applicationName = "azora-build"
}

dependencies {
    implementation(projects.compiler)
    implementation(projects.buildConfig)
    implementation(libs.kotlinx.coroutines.core)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    // Allow setting working directory via -Pazora.dir=path
    val azoraDir = providers.gradleProperty("azora.dir")
    if (azoraDir.isPresent) {
        workingDir = File(azoraDir.get())
    }
}
