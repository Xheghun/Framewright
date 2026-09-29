import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class FramewrightDocumentationVerificationTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val markdownFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val missingTargets = mutableListOf<String>()
        markdownFiles.files.sortedBy(File::getPath).forEach { source ->
            markdownLink.findAll(source.readText()).forEach { match ->
                val rawTarget = match.groupValues[1].trim().removeSurrounding("<", ">")
                if (rawTarget.isExternalOrAnchor()) return@forEach
                val path = rawTarget.substringBefore('#').substringBefore('?')
                if (path.isBlank()) return@forEach
                val target = source.parentFile.resolve(path).normalize()
                if (!target.exists()) {
                    missingTargets += "${source.relativeTo(project.rootDir)} -> $rawTarget"
                }
            }
        }
        if (missingTargets.isNotEmpty()) {
            throw GradleException(
                "Broken relative documentation links:\n${missingTargets.joinToString("\n")}",
            )
        }
    }

    private fun String.isExternalOrAnchor(): Boolean =
        startsWith("#") ||
            startsWith("http://") ||
            startsWith("https://") ||
            startsWith("mailto:")

    private companion object {
        val markdownLink = Regex("""\[[^]]*]\(([^)]+)\)""")
    }
}
