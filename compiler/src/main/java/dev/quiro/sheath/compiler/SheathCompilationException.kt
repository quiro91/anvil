package dev.quiro.sheath.compiler

import org.jetbrains.kotlin.codegen.CompilationException
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi

class SheathCompilationException(
  message: String,
  cause: Throwable? = null,
  element: PsiElement? = null
) : CompilationException(message, cause, element) {
  constructor(
    classDescriptor: ClassDescriptor,
    message: String,
    cause: Throwable? = null
  ) : this(message, cause = cause, element = classDescriptor.identifier)
}

private val ClassDescriptor.identifier: PsiElement?
  get() = (findPsi() as? PsiNameIdentifierOwner)?.identifyingElement
