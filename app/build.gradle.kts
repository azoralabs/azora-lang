plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("dev.azora.lang.MainKt")
    applicationName = "azora"
}

dependencies {
    implementation(projects.compiler)
    implementation(projects.buildConfig)
    implementation(projects.buildTool)
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
}
