// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.refactoring.inline

import com.intellij.history.LocalHistory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.MultiMap
import com.jetbrains.python.PyBundle
import com.jetbrains.python.codeInsight.controlflow.ControlFlowCache
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil
import com.jetbrains.python.codeInsight.imports.PyImportOptimizer
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.impl.PyBuiltinCache
import com.jetbrains.python.psi.impl.PyCallExpressionHelper
import com.jetbrains.python.psi.impl.PyPsiUtils
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.resolve.PyResolveUtil
import com.jetbrains.python.psi.types.TypeEvalContext
import com.jetbrains.python.pyi.PyiUtil
import com.jetbrains.python.refactoring.PyRefactoringUtil
import com.jetbrains.python.refactoring.classes.PyClassRefactoringUtil
import org.jetbrains.annotations.PropertyKey

/**
 * @author Aleksei.Kniazev
 */
class PyInlineFunctionProcessor(project: Project,
                                private val myEditor: Editor,
                                private val myFunction: PyFunction,
                                private val myReference: PsiReference?,
                                private val myInlineThis: Boolean,
                                removeDeclaration: Boolean) : BaseRefactoringProcessor(project) {

  private val myFunctionClass = myFunction.containingClass
  private val myGenerator = PyElementGenerator.getInstance(myProject)
  private var myRemoveDeclaration = !myInlineThis && removeDeclaration

  override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
    if (refUsages.isNull) return false
    val conflicts = MultiMap.create<PsiElement, String>()
    val usagesAndImports = refUsages.get()
    val (imports, usages) = usagesAndImports.partition { PsiTreeUtil.getParentOfType(it.element, PyImportStatementBase::class.java) != null }
    val filteredUsages = usages.filter { usage ->
      val element = usage.element!!
      if (element.parent is PyDecorator) {
        if (!handleUsageError(element, "refactoring.inline.function.is.decorator", conflicts)) return false
        return@filter false
      }
      else if (element.parent !is PyCallExpression) {
        if (!handleUsageError(element, "refactoring.inline.function.is.reference", conflicts)) return false
        return@filter false
      }
      else {
        val callExpression = element.parent as PyCallExpression
        if (callExpression.arguments.any { it is PyStarArgument}) {
          if (!handleUsageError(element, "refactoring.inline.function.uses.unpacking", conflicts)) return false
          return@filter false
        }
      }
      return@filter true
    }

    val conflictLocations = conflicts.keySet().map { it.containingFile }
    val filteredImports = imports.filter { it.file !in conflictLocations }
    val filtered = filteredUsages + filteredImports
    refUsages.set(filtered.toTypedArray())
    return showConflicts(conflicts, filtered.toTypedArray())
  }

  private fun handleUsageError(element: PsiElement, @PropertyKey(resourceBundle = "com.jetbrains.python.PyBundle") error: String, conflicts: MultiMap<PsiElement, String>): Boolean {
    val errorText = PyBundle.message(error, myFunction.name)
    if (myInlineThis) {
      // shortcut for inlining single reference: show error hint instead of modal dialog
      CommonRefactoringUtil.showErrorHint(myProject, myEditor, errorText, PyBundle.message("refactoring.inline.function.title"), PyInlineFunctionHandler.REFACTORING_ID)
      prepareSuccessful()
      return false
    }
    conflicts.putValue(element, errorText)
    myRemoveDeclaration = false
    return true
  }

  override fun findUsages(): Array<UsageInfo> {
    if (myInlineThis) {
      val element = myReference!!.element as PyReferenceExpression
      val import = PyResolveUtil.resolveLocally(ScopeUtil.getScopeOwner(element)!!, element.name!!).firstOrNull { it is PyImportElement }
      return if (import != null) arrayOf(UsageInfo(element), UsageInfo(import)) else arrayOf(UsageInfo(element))
    }

    return ReferencesSearch.search(myFunction, myRefactoringScope).asSequence()
      .distinct()
      .map(PsiReference::getElement)
      .map(::UsageInfo)
      .toList()
      .toTypedArray()
  }

  override fun performRefactoring(usages: Array<out UsageInfo>) {
    val action = LocalHistory.getInstance().startAction(commandName)
    try {
      doRefactor(usages)
    }
    finally {
      action.finish()
    }
  }

  private fun doRefactor(usages: Array<out UsageInfo>) {
    val (unsortedRefs, imports) = usages.partition { PsiTreeUtil.getParentOfType(it.element, PyImportStatementBase::class.java) == null }

    val references = unsortedRefs.sortedByDescending { usage ->
      SyntaxTraverser.psiApi().parents(usage.element).asSequence().filter { it is PyCallExpression }.count()
    }

    val functionScope = ControlFlowCache.getScope(myFunction)
    PyClassRefactoringUtil.rememberNamedReferences(myFunction)

    references.forEach { usage ->
      val reference = usage.element as PyReferenceExpression
      val languageLevel = LanguageLevel.forElement(reference)
      val refScopeOwner = ScopeUtil.getScopeOwner(reference) ?: error("Unable to find scope owner for ${reference.name}")
      val declarations = mutableListOf<PyAssignmentStatement>()
      val generatedNames = mutableSetOf<String>()


      val callSite = PsiTreeUtil.getParentOfType(reference, PyCallExpression::class.java) ?: error("Unable to find call expression for ${reference.name}")
      val containingStatement = PsiTreeUtil.getParentOfType(callSite, PyStatement::class.java) ?: error("Unable to find statement for ${reference.name}")

      val replacementFunction = myFunction.statementList.copy() as PyStatementList
      val namesInOuterScope = PyRefactoringUtil.collectUsedNames(refScopeOwner)

      val argumentReplacements = mutableMapOf<PyReferenceExpression, PyExpression>()
      val nameClashes = MultiMap.create<String, PyExpression>()
      val returnStatements = mutableListOf<PyReturnStatement>()

      val mappedArguments = prepareArguments(callSite, declarations, generatedNames, reference, languageLevel)

      val builtinCache = PyBuiltinCache.getInstance(reference)
      replacementFunction.accept(object : PyRecursiveElementVisitor() {
        override fun visitPyReferenceExpression(node: PyReferenceExpression) {
          if (node.qualifier == null) {
            val name = node.name
            if (name in mappedArguments) {
              argumentReplacements[node] = mappedArguments[name]!!
            }
            else if (name in namesInOuterScope && !builtinCache.isBuiltin(node.reference.resolve())) {
              nameClashes.putValue(name!!, node)
            }
          }
          super.visitPyReferenceExpression(node)
        }

        override fun visitPyReturnStatement(node: PyReturnStatement) {
          returnStatements.add(node)
          super.visitPyReturnStatement(node)
        }

        override fun visitPyTargetExpression(node: PyTargetExpression) {
          if (node.qualifier == null) {
            val name = node.name
            if (name in namesInOuterScope && name !in mappedArguments && functionScope.containsDeclaration(name)) {
              nameClashes.putValue(name!!, node)
            }
          }
          super.visitPyTargetExpression(node)
        }
      })

      // Replacing
      argumentReplacements.forEach { (old, new) -> old.replace(new) }
      nameClashes.entrySet().forEach { (name, elements) ->
        val generated = generateUniqueAssignment(languageLevel, name, generatedNames, reference)
        elements.forEach {
            when (it) {
              is PyTargetExpression -> it.replace(generated.targets[0])
              is PyReferenceExpression -> it.replace(generated.assignedValue!!)
            }
          }
      }


      if (returnStatements.size == 1 && returnStatements[0].expression !is PyTupleExpression) {
        // replace single return with expression itself
        val statement = returnStatements[0]
        callSite.replace(statement.expression!!)
        statement.delete()
      }
      else if (returnStatements.isNotEmpty())  {
        val newReturn = generateUniqueAssignment(languageLevel, "result", generatedNames, reference)
        returnStatements.forEach {
          val copy = newReturn.copy() as PyAssignmentStatement
          copy.assignedValue!!.replace(it.expression!!)
          it.replace(copy)
        }
        callSite.replace(newReturn.assignedValue!!)
      }

      CodeStyleManager.getInstance(myProject).reformat(replacementFunction, true)

      declarations.forEach { containingStatement.parent.addBefore(it, containingStatement) }
      if (replacementFunction.firstChild != null) {
        val statements = replacementFunction.statements
        statements.asSequence()
          .map { containingStatement.parent.addBefore(it, containingStatement) }
          .forEach { PyClassRefactoringUtil.restoreNamedReferences(it) }
      }

      if (returnStatements.isEmpty()) {
        if (callSite.parent is PyExpressionStatement) {
          containingStatement.delete()
        }
        else {
          callSite.replace(myGenerator.createExpressionFromText(languageLevel, "None"))
        }
      }
    }

    imports.forEach { PyClassRefactoringUtil.optimizeImports(it.element!!.containingFile!!) }

    if (myRemoveDeclaration) {
      val stubFunction = PyiUtil.getPythonStub(myFunction)
      if (stubFunction != null && stubFunction.isWritable) {
        stubFunction.delete()
      }
      myFunction.delete()
    }
  }

  private fun prepareArguments(callSite: PyCallExpression, declarations: MutableList<PyAssignmentStatement>, generatedNames: MutableSet<String>,
                               reference: PyReferenceExpression, languageLevel: LanguageLevel): Map<String, PyExpression> {
    val context = PyResolveContext.noImplicits().withTypeEvalContext(TypeEvalContext.userInitiated(myProject, reference.containingFile))
    val mapping = PyCallExpressionHelper.mapArguments(callSite, context).firstOrNull() ?: error("Can't map arguments for ${reference.name}")
    val mappedParams = mapping.mappedParameters


    val self = mapping.implicitParameters.firstOrNull()?.let { first ->
      val implicitName = first.name!!
      val selfReplacement = reference.qualifier?.let { qualifier ->
        myFunctionClass?.let {
          when {
            qualifier is PyReferenceExpression && !qualifier.isQualified -> qualifier
            else ->  {
              val qualifierDeclaration = generateUniqueAssignment(languageLevel, myFunctionClass.name!!, generatedNames, reference)
              val newRef = qualifierDeclaration.assignedValue!!.copy() as PyExpression
              qualifierDeclaration.assignedValue!!.replace(qualifier)
              declarations.add(qualifierDeclaration)
              newRef
            }
          }
        }
      }
      mapOf(implicitName to selfReplacement!!)
    } ?: emptyMap()

    val passedArguments = mappedParams.asSequence()
      .map { (arg, param) ->
        val argValue = if (arg is PyKeywordArgument) arg.valueExpression!! else arg
        tryExtractDeclaration(param.name!!, argValue, declarations, generatedNames, reference, languageLevel)
      }
      .toMap()

    val defaultValues = myFunction.parameterList.parameters.asSequence()
      .filter { it.name !in passedArguments }
      .filter { it.hasDefaultValue() }
      .map { tryExtractDeclaration(it.name!!, it.defaultValue!!, declarations, generatedNames, reference, languageLevel) }
      .toMap()

    return self + passedArguments + defaultValues
  }

  private fun tryExtractDeclaration(paramName: String, arg: PyExpression, declarations: MutableList<PyAssignmentStatement>, generatedNames: MutableSet<String>,
                                    reference: PyReferenceExpression, languageLevel: LanguageLevel): Pair<String, PyExpression> {
    if (arg !is PyReferenceExpression && arg !is PyLiteralExpression) {
      val statement = generateUniqueAssignment(languageLevel, paramName, generatedNames, reference)
      statement.assignedValue!!.replace(arg)
      declarations.add(statement)
      return paramName to statement.targets[0]
    }
    return paramName to arg

  }

  private fun generateUniqueAssignment(level: LanguageLevel, name: String, previouslyGeneratedNames: MutableSet<String>, scopeAnchor: PsiElement): PyAssignmentStatement {
    val uniqueName = PyRefactoringUtil.selectUniqueName(name, scopeAnchor) { newName, anchor ->
      PyRefactoringUtil.isValidNewName(newName, anchor) && newName !in previouslyGeneratedNames
    }
    previouslyGeneratedNames.add(uniqueName)
    return myGenerator.createFromText(level, PyAssignmentStatement::class.java, "$uniqueName = $uniqueName")
  }

  override fun getCommandName() = "Inlining ${myFunction.name}"
  override fun getRefactoringId() = PyInlineFunctionHandler.REFACTORING_ID

  override fun createUsageViewDescriptor(usages: Array<out UsageInfo>) = object : UsageViewDescriptor {
    override fun getElements(): Array<PsiElement> = arrayOf(myFunction)
    override fun getProcessedElementsHeader(): String = "Function to inline "
    override fun getCodeReferencesText(usagesCount: Int, filesCount: Int): String = "Invocations to be inlined in $filesCount files"
    override fun getCommentReferencesText(usagesCount: Int, filesCount: Int): String = ""
  }
}
