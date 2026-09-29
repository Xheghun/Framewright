import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class FramewrightPublicationVerificationTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val publicationVersion: Property<String>

    @TaskAction
    fun verify() {
        val version = publicationVersion.get()
        val groupDirectory = repositoryDirectory.dir("io/github/xheghun").get().asFile
        val artifacts =
            linkedMapOf(
                "framewright-analytics" to "jar",
                "framewright-media3-adapter" to "aar",
                "framewright-bandwidth-monitor" to "aar",
                "framewright-codec-inspector" to "aar",
                "framewright-drm-inspector" to "aar",
                "framewright-diagnostics-overlay" to "aar",
                "framewright-storage" to "aar",
            )

        artifacts.forEach { (artifactId, extension) ->
            val artifactDirectory = groupDirectory.resolve("$artifactId/$version")
            requireArtifact(artifactDirectory, artifactId) { name ->
                name.endsWith(".$extension") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
            }
            requireArtifact(artifactDirectory, artifactId) { it.endsWith("-sources.jar") }
            requireArtifact(artifactDirectory, artifactId) { it.endsWith("-javadoc.jar") }
            requireArtifact(artifactDirectory, artifactId) { it.endsWith(".module") }
            val pom = requireArtifact(artifactDirectory, artifactId) { it.endsWith(".pom") }.readText()
            requirePomMetadata(pom, artifactId, version)
            requirePomDependency(pom, "org.jetbrains.kotlin", "kotlin-stdlib", artifactId, "2.0.21")
            if (artifactId != "framewright-analytics") {
                requirePomDependency(pom, "io.github.xheghun", "framewright-analytics", artifactId)
            }
        }

        listOf("framewright-app", "framewright-media-lab").forEach { unpublishedArtifact ->
            if (groupDirectory.resolve("$unpublishedArtifact/$version").exists()) {
                throw GradleException("Unpublished module was found in the local repository: $unpublishedArtifact")
            }
        }
    }

    private fun requireFile(file: java.io.File): java.io.File {
        if (!file.isFile || file.length() == 0L) {
            throw GradleException("Missing or empty publication file: ${file.absolutePath}")
        }
        return file
    }

    private fun requireArtifact(
        directory: java.io.File,
        artifactId: String,
        matches: (String) -> Boolean,
    ): java.io.File {
        val file =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith("$artifactId-") && matches(it.name) }
                .maxByOrNull { it.lastModified() }
                ?: throw GradleException("Missing publication file in ${directory.absolutePath}")
        return requireFile(file)
    }

    private fun requirePomMetadata(
        pom: String,
        artifactId: String,
        version: String,
    ) {
        val requiredValues =
            listOf(
                "<groupId>io.github.xheghun</groupId>",
                "<artifactId>$artifactId</artifactId>",
                "<version>$version</version>",
                "<name>Framewright",
                "<url>https://github.com/Xheghun/Framewright</url>",
                "<name>The Apache License, Version 2.0</name>",
                "<id>Xheghun</id>",
                "<connection>scm:git:https://github.com/Xheghun/Framewright.git</connection>",
            )
        requiredValues.forEach { required ->
            if (!pom.contains(required)) {
                throw GradleException("$artifactId POM is missing required metadata: $required")
            }
        }
    }

    private fun requirePomDependency(
        pom: String,
        groupId: String,
        artifactId: String,
        ownerArtifactId: String,
        version: String? = null,
    ) {
        val expectedDependency =
            buildString {
                append("<dependency>\\s*<groupId>")
                append(Regex.escape(groupId))
                append("</groupId>\\s*<artifactId>")
                append(Regex.escape(artifactId))
                append("</artifactId>")
                version?.let {
                    append("\\s*<version>")
                    append(Regex.escape(it))
                    append("</version>")
                }
            }
        if (!Regex(expectedDependency).containsMatchIn(pom)) {
            val coordinate = listOfNotNull(groupId, artifactId, version).joinToString(":")
            throw GradleException("$ownerArtifactId POM is missing dependency $coordinate")
        }
    }
}
