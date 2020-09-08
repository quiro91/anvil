package com.squareup.anvil.compiler.codegen.dagger

import com.squareup.anvil.compiler.codegen.CodeGenerator
import com.squareup.anvil.compiler.codegen.addGeneratedByComment
import com.squareup.anvil.compiler.codegen.classesAndInnerClasses
import com.squareup.anvil.compiler.codegen.findAnnotation
import com.squareup.anvil.compiler.codegen.fqNameOrNull
import com.squareup.anvil.compiler.codegen.functions
import com.squareup.anvil.compiler.codegen.hasAnnotation
import com.squareup.anvil.compiler.codegen.replaceImports
import com.squareup.anvil.compiler.codegen.requireTypeName
import com.squareup.anvil.compiler.codegen.withJvmSuppressWildcardsIfNeeded
import com.squareup.anvil.compiler.codegen.writeToString
import com.squareup.anvil.compiler.daggerContributesAndroidInjector
import com.squareup.anvil.compiler.daggerModuleFqName
import com.squareup.anvil.compiler.generateClassName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import dagger.Binds
import dagger.Module
import dagger.Subcomponent
import dagger.android.AndroidInjector
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtValueArgument
import java.io.File

internal class ContributesAndroidInjectorGenerator : CodeGenerator {
  override fun generateCode(
    codeGenDir: File,
    module: ModuleDescriptor,
    projectFiles: Collection<KtFile>
  ): Collection<CodeGenerator.GeneratedFile> {
    return projectFiles.asSequence()
      .flatMap { it.classesAndInnerClasses() }
      .filter { it.hasAnnotation(daggerModuleFqName) }
      .flatMap { clazz ->
        clazz
          .functions(includeCompanionObjects = true)
          .asSequence()
          .filter { it.hasAnnotation(daggerContributesAndroidInjector) }
          .map { function ->
            generateInjectorClass(codeGenDir, module, clazz, function)
          }
      }
      .toList()
  }

  @OptIn(ExperimentalStdlibApi::class)
  private fun generateInjectorClass(
    codeGenDir: File,
    module: ModuleDescriptor,
    clazz: KtClassOrObject,
    function: KtNamedFunction
  ): CodeGenerator.GeneratedFile {
    val packageName = clazz.containingKtFile.packageFqName.asString()
    val bindingTarget = function.requireTypeName(module)
      .withJvmSuppressWildcardsIfNeeded(function)
    val bindingTargetName = (bindingTarget as ClassName).simpleName
    val className = "${clazz.generateClassName()}_Bind$bindingTargetName"
    val factoryClass = ClassName(packageName, className)
    val contributesAndroidInjector = function.findAnnotation(daggerContributesAndroidInjector)!!
    val contributesAndroidInjectorParams = contributesAndroidInjector.valueArguments
    val scopeAnnotations = function.annotationEntries
      .asSequence()
      .filterNot { it == contributesAndroidInjector }
      .mapNotNull {
        val element = it.children.singleOrNull()?.children?.singleOrNull()
        element?.fqNameOrNull(module) ?: element?.text?.let(::FqName)
      }

    val content = FileSpec.builder(packageName, className)
      .apply {
        val classBuilder = TypeSpec.classBuilder(factoryClass)

        val subcomponentFactoryClassName = ClassName(packageName, "Factory")
        val subcomponentFactory = TypeSpec.interfaceBuilder(subcomponentFactoryClassName)
          .addAnnotation(Subcomponent.Factory::class.java)
          .addSuperinterface(
            AndroidInjector.Factory::class.asClassName().parameterizedBy(bindingTarget)
          )
          .build()

        val subcomponentClassName = "${bindingTargetName}Subcomponent"
        val fullSubcomponentFactoryClassName =
          ClassName(packageName, "$subcomponentClassName.Factory")
        val subcomponent = TypeSpec.interfaceBuilder(ClassName(packageName, subcomponentClassName))
          .addAnnotation(
            AnnotationSpec.builder(Subcomponent::class.java).apply {
              if (contributesAndroidInjectorParams.isNotEmpty()) {
                addMember((contributesAndroidInjectorParams.first() as KtValueArgument).text)
              }
            }.build()
          )
          .apply {
            scopeAnnotations.forEach { annotation ->
              addAnnotation(
                ClassName(
                  annotation.parent().asString(),
                  annotation.shortName().asString()
                )
              )
            }
          }
          .addSuperinterface(AndroidInjector::class.asClassName().parameterizedBy(bindingTarget))
          .addType(subcomponentFactory)
          .build()

        val fullSubcomponentClassName = ClassName.bestGuess(
          "$packageName.$className.$subcomponentClassName"
        )
        classBuilder.primaryConstructor(
          FunSpec.constructorBuilder()
            .addModifiers(KModifier.PRIVATE)
            .build()
        )
          .addAnnotation(
            AnnotationSpec.builder(Module::class)
              .addMember("subcomponents = [%T::class]", fullSubcomponentClassName)
              .build()
          )
          .addModifiers(KModifier.ABSTRACT)
          .addType(subcomponent)
          .addFunction(
            FunSpec.builder("bindAndroidInjectorFactory")
              .addParameter(ParameterSpec("builder", fullSubcomponentFactoryClassName))
              .addModifiers(KModifier.ABSTRACT)
              .addAnnotation(Binds::class)
              .addAnnotation(IntoMap::class)
              .addAnnotation(
                AnnotationSpec.builder(ClassKey::class)
                  .addMember("%T::class", bindingTarget)
                  .build()
              )
              .returns(AndroidInjector.Factory::class.asClassName().parameterizedBy(STAR))
              .build()
          )
          .build()
          .let { addType(it) }
      }
      .build()
      .writeToString()
      .replaceImports(clazz)
      .addGeneratedByComment()

    val directory = File(codeGenDir, packageName.replace('.', File.separatorChar))
    val file = File(directory, "$className.kt")
    check(file.parentFile.exists() || file.parentFile.mkdirs()) {
      "Could not generate package directory: ${file.parentFile}"
    }
    file.writeText(content)

    return CodeGenerator.GeneratedFile(file, content)
  }
}
