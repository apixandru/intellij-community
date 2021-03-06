// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.uast

import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import org.jetbrains.uast.internal.acceptList
import org.jetbrains.uast.internal.log
import org.jetbrains.uast.visitor.UastTypedVisitor
import org.jetbrains.uast.visitor.UastVisitor

/**
 * A class wrapper to be used in [UastVisitor].
 */
interface UClass : UDeclaration, PsiClass {
  override val psi: PsiClass

  override val javaPsi: PsiClass

  override fun getQualifiedName(): String?

  override fun isInterface(): Boolean

  override fun isAnnotationType(): Boolean

  /**
   * Returns a [UClass] wrapper of the superclass of this class, or null if this class is [java.lang.Object].
   */
  @Deprecated("will return null if existing superclass is not convertable to Uast, use `javaPsi.superClass` instead",
              ReplaceWith("javaPsi.superClass"))
  override fun getSuperClass(): UClass? {
    val superClass = javaPsi.superClass ?: return null
    return UastFacade.convertWithParent(superClass)
  }

  val uastSuperTypes: List<UTypeReferenceExpression>

  /**
   * Returns [UDeclaration] wrappers for the class declarations.
   */
  val uastDeclarations: List<UDeclaration>

  override fun getFields(): Array<UField> =
    javaPsi.fields.map { getLanguagePlugin().convert<UField>(it, this) }.toTypedArray()

  override fun getInitializers(): Array<UClassInitializer> =
    javaPsi.initializers.map { getLanguagePlugin().convert<UClassInitializer>(it, this) }.toTypedArray()

  override fun getMethods(): Array<UMethod> =
    javaPsi.methods.map { getLanguagePlugin().convert<UMethod>(it, this) }.toTypedArray()

  override fun getInnerClasses(): Array<UClass> =
    javaPsi.innerClasses.map { getLanguagePlugin().convert<UClass>(it, this) }.toTypedArray()

  override fun asLogString(): String = log("name = $name")

  override fun accept(visitor: UastVisitor) {
    if (visitor.visitClass(this)) return
    uAnnotations.acceptList(visitor)
    uastDeclarations.acceptList(visitor)
    visitor.afterVisitClass(this)
  }

  override fun asRenderString(): String = buildString {
    append(javaPsi.renderModifiers())
    val kind = when {
      javaPsi.isAnnotationType -> "annotation"
      javaPsi.isInterface -> "interface"
      javaPsi.isEnum -> "enum"
      else -> "class"
    }
    append(kind).append(' ').append(javaPsi.name)
    val superTypes = uastSuperTypes
    if (superTypes.isNotEmpty()) {
      append(" : ")
      append(superTypes.joinToString { it.asRenderString() })
    }
    appendln(" {")
    uastDeclarations.forEachIndexed { _, declaration ->
      appendln(declaration.asRenderString().withMargin)
    }
    append("}")
  }

  override fun <D, R> accept(visitor: UastTypedVisitor<D, R>, data: D): R =
    visitor.visitClass(this, data)
}

interface UAnonymousClass : UClass, PsiAnonymousClass {
  override val psi: PsiAnonymousClass
}

@Deprecated("no more needed, use UClass", ReplaceWith("UClass"))
interface UClassTypeSpecific : UClass