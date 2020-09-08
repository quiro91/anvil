package dev.quiro.sheath.plugin

import com.google.auto.service.AutoService
import org.gradle.api.Project
import org.gradle.api.tasks.compile.AbstractCompile
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonOptions
import org.jetbrains.kotlin.gradle.internal.KaptTask
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinGradleSubplugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

@AutoService(KotlinGradleSubplugin::class)
@Suppress("unused")
class SheathSubplugin : KotlinGradleSubplugin<AbstractCompile> {

  override fun isApplicable(
    project: Project,
    task: AbstractCompile
  ): Boolean = project.plugins.hasPlugin(SheathPlugin::class.java)

  override fun getCompilerPluginId(): String = "dev.quiro.sheath.compiler"

  override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
      groupId = GROUP,
      artifactId = "compiler",
      version = VERSION
  )

  override fun apply(
    project: Project,
    kotlinCompile: AbstractCompile,
    javaCompile: AbstractCompile?,
    variantData: Any?,
    androidProjectHandler: Any?,
    kotlinCompilation: KotlinCompilation<KotlinCommonOptions>?
  ): List<SubpluginOption> {
    // Notice that we use the name of the Kotlin compile task as a directory name. Generated code
    // for this specific compile task will be included in the task output. The output of different
    // compile tasks shouldn't be mixed.
    val srcGenDir = File(project.buildDir, "sheath${File.separator}src-gen-${kotlinCompile.name}")
    val srcGenDirPath = srcGenDir.absolutePath
    val srcGenDirOption = SubpluginOption(
        key = "src-gen-dir",
        value = srcGenDirPath
    )

    project.afterEvaluate {
      // Notice that we pass the absolutePath to the Kotlin compiler plugin. That is necessary,
      // because the plugin has no understanding of Gradle or what the working directory is. The
      // Kotlin Gradle plugin adds all SubpluginOptions as input to the Gradle task. This is bad,
      // because now the hash of inputs is different on every machine. We override this input with
      // a relative path in order to preserve some safety and avoid the cache misses.
      val key = "${getCompilerPluginId()}.${srcGenDirOption.key}"
      val relativePath = srcGenDir.relativeTo(project.buildDir).path

      project.tasks.withType(KotlinCompile::class.java) { task ->
        task.inputs.property(key, relativePath)
      }
      project.tasks.withType(KaptTask::class.java) { task ->
        // We don't apply the compiler plugin for KaptTasks. But for some reason the Kotlin Gradle
        // plugin copies the inputs with the "kotlinCompile" prefix.
        task.inputs.property("kotlinCompile.$key", relativePath)
      }
    }

    val extension = project.extensions.findByType(SheathExtension::class.java) ?: SheathExtension()

    return listOf(
        srcGenDirOption,
        SubpluginOption(
            key = "generate-dagger-factories",
            value = "true"
        )
    )
  }
}
