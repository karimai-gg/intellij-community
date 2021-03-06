// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.editorActions

import com.intellij.codeInsight.actions.ReaderModeSettings.Companion.applyReaderMode
import com.intellij.codeInsight.actions.ReaderModeSettings.Companion.instance
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider

class ReaderModeFileEditorListener : FileEditorManagerListener {
  override fun fileOpenedSync(source: FileEditorManager, file: VirtualFile, editors: Pair<Array<FileEditor>, Array<FileEditorProvider>>) {
    val project = source.project
    val selectedEditor = source.getSelectedEditor(file)
    if (selectedEditor !is PsiAwareTextEditorImpl) return

    if (!instance(project).enabled) return
    applyReaderMode(project, selectedEditor.editor, file)
  }
}

class ReaderModeWritingAccessProvider(val project: Project) : WritingAccessProvider() {
  override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> {
    files.forEach {
      val selectedEditor = FileEditorManager.getInstance(project).getSelectedEditor(it)
      if (selectedEditor !is PsiAwareTextEditorImpl || !instance(project).enabled) return emptyList()

      applyReaderMode(project, selectedEditor.editor, it, true)
    }
    return emptyList()
  }
}