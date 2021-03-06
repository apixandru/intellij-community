// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.project.impl

import com.intellij.idea.Bombed
import com.intellij.openapi.application.AppUIExecutor
import com.intellij.openapi.application.JBProtocolCommand
import com.intellij.openapi.application.impl.coroutineDispatchingContext
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.*
import com.intellij.testFramework.assertions.Assertions.assertThat
import com.intellij.util.io.sanitizeFileName
import com.intellij.util.io.systemIndependentPath
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.nio.file.Paths

class JBNavigateCommandTest {
  companion object {
    @JvmField
    @ClassRule
    val appRule = ApplicationRule()
  }

  @JvmField
  @Rule
  val tempDir = TemporaryDirectory()

  @JvmField
  @Rule
  val testName = TestName()

  fun getTestDataPath(): String {
    return "${PlatformTestUtil.getPlatformTestDataPath()}/commands/navigate/"
  }

  @Test
  fun path1() = runBlocking {
    createOrLoadProject(tempDir, useDefaultProjectSettings = false) { project ->
      configure(project)
      navigate(project.name, mapOf("path" to "A.java"))
      assertThat(getCurrentElement(project).name).isEqualTo("A.java")
    }
  }

  @Bombed(year = 2019, month = 7, day = 1, user = "dima")
  fun testFqn1() {
    //val project = configureProject()
    //
    //navigate(project.name, mapOf("fqn" to "A"))
    //
    //UIUtil.dispatchAllInvocationEvents()
    //TestCase.assertEquals(getCurrentElement(project).name, "A")
    //PlatformTestUtil.forceCloseProjectWithoutSaving(project)
  }

  @Bombed(year = 2019, month = 7, day = 1, user = "dima")
  fun testFqnMethod() {
    //val project = configureProject()
    //
    //navigate(project.name, mapOf("fqn" to "A#main"))
    //
    //UIUtil.dispatchAllInvocationEvents()
    //TestCase.assertEquals(getCurrentElement(project).name, "main")
    //PlatformTestUtil.forceCloseProjectWithoutSaving(project)
  }

  @Bombed(year = 2019, month = 7, day = 1, user = "dima")
  fun testFqnMultipleMethods() {
    //val project = configureProject()
    //
    //navigate(project.name, mapOf("fqn1" to "A1#main1",
    //                             "fqn2" to "A2#main2"))
    //
    //UIUtil.dispatchAllInvocationEvents()
    //val elements = getCurrentElements(project)
    //TestCase.assertEquals(elements[0].name, "main1")
    //TestCase.assertEquals(elements[1].name, "main2")
    //PlatformTestUtil.forceCloseProjectWithoutSaving(project)
  }

  @Bombed(year = 2019, month = 7, day = 1, user = "dima")
  fun testFqnConstant() {
    //val project = configureProject()
    //
    //navigate(project.name, mapOf("fqn" to "A#RUN_CONFIGURATION_AD_TEXT"))
    //
    //UIUtil.dispatchAllInvocationEvents()
    //TestCase.assertEquals(getCurrentElement(project).name, "RUN_CONFIGURATION_AD_TEXT")
    //PlatformTestUtil.forceCloseProjectWithoutSaving(project)
  }

  @Test
  fun pathOpenProject() = runBlocking {
    var projectName: String? = null
    createOrLoadProject(tempDir, useDefaultProjectSettings = false) { project ->
      configure(project)
      projectName = project.name
    }

    withContext(AppUIExecutor.onUiThread().coroutineDispatchingContext()) {
      var recentProject: Project? = null
      try {
        navigate(projectName!!, mapOf("path" to "A.java"))
        UIUtil.dispatchAllInvocationEvents()

        recentProject = ProjectManager.getInstance().openProjects.find { it.name == projectName }!!
        assertThat(getCurrentElement(recentProject).name).isEqualTo("A.java")
      }
      finally {
        PlatformTestUtil.forceCloseProjectWithoutSaving(recentProject ?: return@withContext)
      }
    }
  }

  private fun configure(project: Project) {
    val basePath = Paths.get(project.basePath!!)
    val moduleManager = ModuleManager.getInstance(project)
    val projectManager = ProjectRootManagerEx.getInstanceEx(project)
    runWriteAction {
      projectManager.mergeRootsChangesDuring {
        val newModule = moduleManager.newModule(basePath.resolve("navigationCommandModule.uml").systemIndependentPath, EmptyModuleType.EMPTY_MODULE)
        FileUtil.copyDir(Paths.get(getTestDataPath(), sanitizeFileName(testName.methodName)).toFile(), basePath.toFile())

        val baseDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath.systemIndependentPath)!!
        val moduleModel = ModuleRootManager.getInstance(newModule).modifiableModel
        moduleModel.addContentEntry(baseDir).addSourceFolder(baseDir, false)
        moduleModel.commit()

        VfsTestUtil.createDir(baseDir, Project.DIRECTORY_STORE_FOLDER)
      }
    }
  }

  private fun getCurrentElement(project: Project): NavigatablePsiElement {
    return getCurrentElements(project)[0]
  }

  private fun getCurrentElements(project: Project): List<NavigatablePsiElement> {
    return FileEditorManager.getInstance(project).allEditors.map {
      val textEditor = it as TextEditor
      val offset = textEditor.editor.caretModel.offset
      val file = it.file
      val psiFile = PsiManager.getInstance(project).findFile(file!!)

      PsiTreeUtil.findElementOfClassAtOffset(psiFile!!, offset, NavigatablePsiElement::class.java, false)!!
    }
  }

  private fun navigate(projectName: String, parameters: Map<String, String>) {
    val navigateCommand = JBProtocolCommand.findCommand("navigate")
    val map = hashMapOf("project" to projectName)
    map.putAll(parameters)
    navigateCommand?.perform("reference", map)
  }
}
