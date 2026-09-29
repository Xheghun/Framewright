plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("framewright.publishing")
}

dependencies {
    api(libs.kolinx.coroutines)
    api(libs.kolinx.serialization)

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
