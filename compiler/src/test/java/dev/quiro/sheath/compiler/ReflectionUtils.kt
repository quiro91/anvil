package dev.quiro.sheath.compiler

import java.lang.reflect.Member
import java.lang.reflect.Modifier

val Member.isStatic: Boolean get() = Modifier.isStatic(modifiers)
val Member.isAbstract: Boolean get() = Modifier.isAbstract(modifiers)
