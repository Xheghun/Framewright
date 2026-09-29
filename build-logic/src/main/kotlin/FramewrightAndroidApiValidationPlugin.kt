import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

/**
 * Supplies the missing BCV task wiring for AGP 9 Android libraries using built-in Kotlin.
 * Remove this bridge once Kotlin's built-in ABI validation supports com.android.library.
 */
class FramewrightAndroidApiValidationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.library") {
            project.afterEvaluate {
                val kotlinClasses = project.tasks.named("compileReleaseKotlin").map { it.outputs.files }
                val javaClasses = project.tasks.named("compileReleaseJavaWithJavac").map { it.outputs.files }
                val committedApi = project.layout.projectDirectory.file("api/${project.name}.api")

                val apiBuild =
                    project.tasks.register<KotlinApiBuildTask>("releaseApiBuild") {
                        inputClassesDirs.from(kotlinClasses)
                        inputClassesDirs.from(javaClasses)
                        outputApiFile.set(project.layout.buildDirectory.file("api-validation/${project.name}.api"))
                    }
                val apiCheck =
                    project.tasks.register<KotlinApiCompareTask>("releaseApiCheck") {
                        projectApiFile.set(committedApi)
                        generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
                    }
                project.tasks.register<FramewrightApiDumpTask>("releaseApiDump") {
                    group = "verification"
                    description = "Updates the committed release API dump."
                    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
                    committedApiFile.set(committedApi)
                }
                project.tasks.named("check").configure { dependsOn(apiCheck) }
            }
        }
    }
}

@DisableCachingByDefault(because = "Copies a generated API signature into the source tree")
abstract class FramewrightApiDumpTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedApiFile: RegularFileProperty

    @get:OutputFile
    abstract val committedApiFile: RegularFileProperty

    @TaskAction
    fun updateDump() {
        val generated = generatedApiFile.get().asFile
        val committed = committedApiFile.get().asFile
        committed.parentFile.mkdirs()
        generated.copyTo(committed, overwrite = true)
    }
}
