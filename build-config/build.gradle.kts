plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies { }
    }
}
