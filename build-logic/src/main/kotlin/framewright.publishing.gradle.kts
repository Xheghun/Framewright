import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.AbiValidationExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
}

apply<FramewrightAndroidApiValidationPlugin>()

val publicationVersion = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT")
val artifactId = "framewright-${project.name}"
val artifactDisplayNames =
    mapOf(
        "analytics" to "Framewright Analytics",
        "media3-adapter" to "Framewright Media3 Adapter",
        "bandwidth-monitor" to "Framewright Bandwidth Monitor",
        "codec-inspector" to "Framewright Codec Inspector",
        "drm-inspector" to "Framewright DRM Inspector",
        "diagnostics-overlay" to "Framewright Diagnostics Overlay",
        "storage" to "Framewright Storage",
    )
val artifactDescriptions =
    mapOf(
        "analytics" to "Player-agnostic playback diagnostics events, aggregation, summaries, and JSON export.",
        "media3-adapter" to "Attach-first AndroidX Media3 playback instrumentation for host-owned ExoPlayer instances.",
        "bandwidth-monitor" to "Dual-EWMA Media3 bandwidth estimation and adaptive-bitrate diagnostics.",
        "codec-inspector" to "Android decoder capability discovery and selected-format support inspection.",
        "drm-inspector" to "Widevine and Media3 DRM lifecycle, request, key-status, and device-security diagnostics.",
        "diagnostics-overlay" to "A Compose diagnostics overlay for the Framewright playback event stream.",
        "storage" to "Room-backed persistence, retrieval, export, and sharing of Framewright diagnostic sessions.",
    )

group = "io.github.xheghun"
version = publicationVersion.get()

extensions.configure<KotlinBaseExtension> {
    coreLibrariesVersion = "2.0.21"
}

pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    tasks.named("check") {
        dependsOn("checkLegacyAbi")
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

@OptIn(ExperimentalAbiValidation::class)
fun configureAbiValidation() {
    val kotlinExtension = extensions.findByName("kotlin") as? ExtensionAware ?: return
    kotlinExtension.extensions
        .findByType(AbiValidationExtension::class.java)
        ?.enabled
        ?.set(true)
}

configureAbiValidation()

extensions.configure<PublishingExtension> {
    repositories {
        maven {
            name = "localTest"
            url =
                rootProject.layout.buildDirectory
                    .dir("local-maven-repository")
                    .get()
                    .asFile
                    .toURI()
        }
    }
}

mavenPublishing {
    coordinates(group.toString(), artifactId, version.toString())
    configureBasedOnAppliedPlugins(
        javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
        sourcesJar = SourcesJar.Sources(),
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set(artifactDisplayNames.getValue(project.name))
        description.set(artifactDescriptions.getValue(project.name))
        inceptionYear.set("2026")
        url.set("https://github.com/Xheghun/Framewright")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Xheghun")
                name.set("Xheghun")
                url.set("https://github.com/Xheghun")
            }
        }
        scm {
            url.set("https://github.com/Xheghun/Framewright")
            connection.set("scm:git:https://github.com/Xheghun/Framewright.git")
            developerConnection.set("scm:git:ssh://git@github.com/Xheghun/Framewright.git")
        }
    }
}
