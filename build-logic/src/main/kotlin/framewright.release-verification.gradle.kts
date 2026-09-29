plugins {
    base
}

val publishedModules =
    listOf(
        ":analytics",
        ":media3-adapter",
        ":bandwidth-monitor",
        ":codec-inspector",
        ":drm-inspector",
        ":diagnostics-overlay",
        ":storage",
    )
val releaseVersionProvider = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT")

val verifyDocumentation =
    tasks.register<FramewrightDocumentationVerificationTask>("verifyDocumentation") {
        group = "verification"
        description = "Checks relative links in the repository's Markdown documentation."
        markdownFiles.from(
            layout.projectDirectory.file("README.md"),
            layout.projectDirectory.file("CHANGELOG.md"),
            fileTree(layout.projectDirectory.dir("docs")) { include("**/*.md") },
        )
    }

val publishAllToLocalTestRepository =
    tasks.register("publishAllToLocalTestRepository") {
        group = "publishing"
        description = "Publishes every public Framewright artifact to the build-local Maven repository."
        dependsOn(publishedModules.map { "$it:publishMavenPublicationToLocalTestRepository" })
    }

tasks.register("publishAndReleaseAllToMavenCentral") {
    group = "publishing"
    description = "Publishes and automatically releases every public Framewright artifact on Maven Central."
    dependsOn(publishedModules.map { "$it:publishAndReleaseToMavenCentral" })
}

tasks.register<FramewrightPublicationVerificationTask>("verifyLocalPublications") {
    group = "verification"
    description = "Verifies the locally published artifacts and their Maven metadata."
    dependsOn(publishAllToLocalTestRepository)
    repositoryDirectory.set(layout.buildDirectory.dir("local-maven-repository"))
    publicationVersion.set(releaseVersionProvider)
}

tasks.named("check") {
    dependsOn(verifyDocumentation)
}

tasks.register("verifyReleaseReadiness") {
    group = "verification"
    description = "Runs API, documentation, snippet, and local publication release checks."
    dependsOn(verifyDocumentation)
    dependsOn("verifyLocalPublications")
    dependsOn(publishedModules.map { "$it:check" })
    dependsOn(":docs-snippets:check")
}
