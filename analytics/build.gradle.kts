plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(kotlin("stdlib"))

    implementation(libs.kolinx.coroutines)
    implementation(libs.kolinx.serialization)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertk)
    testImplementation(libs.turbine)
    testImplementation(libs.kolinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
