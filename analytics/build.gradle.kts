plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(kotlin("stdlib"))

    implementation(libs.kolinx.coroutines)
    implementation(libs.kolinx.serialization)

    testImplementation(libs.junit)
    testImplementation(libs.kolinx.coroutines.test)
}
