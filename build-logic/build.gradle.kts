plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(libs.vanniktech.maven.publish.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.binary.compatibility.validator)
    implementation(libs.kotlin.metadata.jvm)
    implementation(libs.asm)
    implementation(libs.asm.tree)
}
