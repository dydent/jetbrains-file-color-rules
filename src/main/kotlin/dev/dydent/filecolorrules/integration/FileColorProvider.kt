package dev.dydent.filecolorrules.integration

import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

class FileColorProvider : EditorTabColorProvider, DumbAware {
    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? = null

    override fun getProjectViewColor(project: Project, file: VirtualFile): Color? =
        RuleEngineService.getInstance(project).colorFor(file)
}
