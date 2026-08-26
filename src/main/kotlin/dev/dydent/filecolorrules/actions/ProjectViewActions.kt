package dev.dydent.filecolorrules.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import dev.dydent.filecolorrules.config.ColorRule
import dev.dydent.filecolorrules.config.Condition
import dev.dydent.filecolorrules.config.FileColorConfig
import dev.dydent.filecolorrules.config.PaletteColor
import dev.dydent.filecolorrules.integration.RuleEngineService
import java.security.MessageDigest

private const val CREATE_COLOR = "Create color…"

abstract class ColorPathAction(private val descendants: Boolean) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = event.project
        val relative = if (project != null && file != null) {
            RuleEngineService.getInstance(project).relativePath(file)
        } else {
            null
        }
        event.presentation.isEnabledAndVisible =
            project != null && relative != null && (!descendants || file?.isDirectory == true)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = RuleEngineService.getInstance(project)
        val path = service.relativePath(file) ?: return
        val snapshot = service.snapshot()
        if (snapshot.problem != null) {
            Messages.showErrorDialog(
                project,
                "Fix the current YAML error before adding a rule:\n${snapshot.problem}",
                "File Color Rules",
            )
            return
        }

        val selection = chooseColor(project, snapshot.config) ?: return
        val config = selection.first
        val colorName = selection.second
        val mode = if (descendants) "tree" else "exact"
        val preferredId = directRuleId(mode, path)
        val condition = if (descendants) Condition.UnderPath(path) else Condition.PathEquals(path)
        val existingIndex = config.rules.indexOfFirst { it.id == preferredId }
        val generated = ColorRule(
            id = if (existingIndex >= 0 && !isGeneratedRule(config.rules[existingIndex], path)) {
                uniqueId(preferredId, config)
            } else {
                preferredId
            },
            name = if (descendants) "Color $path and descendants" else "Color $path",
            description = "Created by the File Color Rules Project View context action.",
            enabled = true,
            color = colorName,
            whenCondition = condition,
        )
        val withoutOld = config.rules.filterNot { it.id == generated.id }
        service.replaceConfig(config.copy(rules = listOf(generated) + withoutOld)) {
            Messages.showErrorDialog(project, it, "File Color Rules")
        }
    }

    private fun chooseColor(project: Project, config: FileColorConfig): Pair<FileColorConfig, String>? {
        val choices = config.colors.keys.toMutableList().apply { add(CREATE_COLOR) }
        val index = Messages.showDialog(
            project,
            "Choose a palette color for this rule.",
            "File Color Rules",
            choices.toTypedArray(),
            0,
            null,
        )
        if (index < 0) return null
        val selected = choices[index]
        if (selected != CREATE_COLOR) return config to selected

        val name = Messages.showInputDialog(
            project,
            "Color name (letters, digits, dot, underscore, or hyphen):",
            "Create Palette Color",
            null,
        )?.trim().orEmpty()
        if (!Regex("^[A-Za-z0-9._-]+$").matches(name) || name in config.colors) {
            Messages.showErrorDialog(project, "Enter a new valid color name.", "File Color Rules")
            return null
        }
        val value = Messages.showInputDialog(
            project,
            "Hex color in #RRGGBB form:",
            "Create Palette Color",
            null,
            "#DCEBFF",
            null,
        )?.trim()?.uppercase().orEmpty()
        if (!Regex("^#[0-9A-F]{6}$").matches(value)) {
            Messages.showErrorDialog(project, "Enter a color in #RRGGBB form.", "File Color Rules")
            return null
        }
        val colors = LinkedHashMap(config.colors)
        colors[name] = PaletteColor(value, value)
        return config.copy(colors = colors) to name
    }
}

class ColorExactPathAction : ColorPathAction(false)

class ColorDescendantsAction : ColorPathAction(true)

class RemoveDirectColorAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        if (project == null || file == null) {
            event.presentation.isEnabledAndVisible = false
            return
        }
        val service = RuleEngineService.getInstance(project)
        val path = service.relativePath(file)
        event.presentation.isEnabledAndVisible = path != null &&
            service.snapshot().config.rules.any { it.id in directRuleIds(path) && isGeneratedRule(it, path) }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = RuleEngineService.getInstance(project)
        val path = service.relativePath(file) ?: return
        val ids = directRuleIds(path)
        val config = service.snapshot().config
        val updated = config.rules.filterNot { it.id in ids && isGeneratedRule(it, path) }
        if (updated.size != config.rules.size) {
            service.replaceConfig(config.copy(rules = updated)) {
                Messages.showErrorDialog(project, it, "File Color Rules")
            }
        }
    }
}

class OpenSettingsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "dev.dydent.filecolorrules.settings")
    }
}

private fun directRuleIds(path: String): Set<String> =
    setOf(directRuleId("exact", path), directRuleId("tree", path))

private fun directRuleId(mode: String, path: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
        .digest("$mode\u0000$path".toByteArray())
        .take(6)
        .joinToString("") { "%02x".format(it) }
    return "fcr-direct-$mode-$hash"
}

private fun uniqueId(base: String, config: FileColorConfig): String {
    var suffix = 2
    var candidate = "$base-$suffix"
    while (config.rules.any { it.id == candidate }) {
        suffix += 1
        candidate = "$base-$suffix"
    }
    return candidate
}

private fun isGeneratedRule(rule: ColorRule, path: String): Boolean =
    rule.description == "Created by the File Color Rules Project View context action." &&
        when (val condition = rule.whenCondition) {
            is Condition.PathEquals -> condition.value == path
            is Condition.UnderPath -> condition.value == path
            else -> false
        }
