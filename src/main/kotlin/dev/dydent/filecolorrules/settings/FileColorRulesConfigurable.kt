package dev.dydent.filecolorrules.settings

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import dev.dydent.filecolorrules.config.AtomicConfigWriter
import dev.dydent.filecolorrules.config.ColorRule
import dev.dydent.filecolorrules.config.Condition
import dev.dydent.filecolorrules.config.ConfigException
import dev.dydent.filecolorrules.config.ConfigParser
import dev.dydent.filecolorrules.config.ConfigSerializer
import dev.dydent.filecolorrules.config.FileColorConfig
import dev.dydent.filecolorrules.config.FileKind
import dev.dydent.filecolorrules.config.PaletteColor
import dev.dydent.filecolorrules.engine.FileFacts
import dev.dydent.filecolorrules.engine.RuleCompiler
import dev.dydent.filecolorrules.integration.RuleEngineService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

class FileColorRulesConfigurable(private val project: Project) :
    SearchableConfigurable,
    Configurable.NoScroll {
    private val service = RuleEngineService.getInstance(project)
    private val enabledCheckBox = JBCheckBox("Enable File Color Rules")
    private val yamlEditor = JBTextArea()
    private val statusLabel = JBLabel(" ")
    private val testPathField = JBTextField()
    private val testResultLabel = JBLabel(" ")
    private val ruleTableModel = RuleTableModel()
    private val colorTableModel = ColorTableModel()
    private val ruleTable = JBTable(ruleTableModel)
    private val colorTable = JBTable(colorTableModel)
    private var component: JPanel? = null
    private var loadedSource = ""
    private var loadedFingerprint = AtomicConfigWriter.fingerprint("")
    private var loadedEnabled = true

    override fun getId(): String = "dev.dydent.filecolorrules.settings"

    override fun getDisplayName(): String = "File Color Rules"

    override fun createComponent(): JComponent {
        if (component != null) return component!!
        yamlEditor.font = Font(Font.MONOSPACED, Font.PLAIN, yamlEditor.font.size)
        yamlEditor.lineWrap = false
        yamlEditor.accessibleContext.accessibleName = "File Color Rules YAML"
        ruleTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        colorTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        ruleTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        colorTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        val root = JPanel(BorderLayout(0, 8))
        val header = JPanel(BorderLayout())
        header.add(enabledCheckBox, BorderLayout.WEST)
        header.add(statusLabel, BorderLayout.CENTER)
        root.add(header, BorderLayout.NORTH)

        val tabs = JBTabbedPane()
        tabs.addTab("Rules", rulesPanel())
        tabs.addTab("Palette", palettePanel())
        tabs.addTab("YAML", JBScrollPane(yamlEditor))

        val tester = JPanel(BorderLayout(8, 0))
        tester.add(JBLabel("Test path:"), BorderLayout.WEST)
        tester.add(testPathField, BorderLayout.CENTER)
        tester.add(testResultLabel, BorderLayout.SOUTH)

        val center = JPanel(BorderLayout(0, 8))
        center.add(tabs, BorderLayout.CENTER)
        center.add(tester, BorderLayout.SOUTH)
        root.add(center, BorderLayout.CENTER)
        root.add(fileActionsPanel(), BorderLayout.SOUTH)
        root.preferredSize = Dimension(880, 620)

        val listener = object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = onYamlChanged()
            override fun removeUpdate(event: DocumentEvent) = onYamlChanged()
            override fun changedUpdate(event: DocumentEvent) = onYamlChanged()
        }
        yamlEditor.document.addDocumentListener(listener)
        testPathField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = updateTestResult()
            override fun removeUpdate(event: DocumentEvent) = updateTestResult()
            override fun changedUpdate(event: DocumentEvent) = updateTestResult()
        })
        component = root
        return root
    }

    override fun isModified(): Boolean =
        yamlEditor.text != loadedSource || enabledCheckBox.isSelected != loadedEnabled

    @Throws(ConfigurationException::class)
    override fun apply() {
        val path = service.configPath() ?: throw ConfigurationException("The project has no base directory.")
        val parsed = try {
            ConfigParser.parse(yamlEditor.text)
        } catch (exception: ConfigException) {
            throw ConfigurationException(exception.problem.toString())
        }
        val config = parsed.copy(options = parsed.options.copy(enabled = enabledCheckBox.isSelected))
        val serialized = ConfigSerializer.serialize(config)
        val diskSource = if (Files.exists(path)) Files.readString(path, StandardCharsets.UTF_8) else ""
        if (AtomicConfigWriter.fingerprint(diskSource) != loadedFingerprint && isModified) {
            when (
                Messages.showDialog(
                    project,
                    "The YAML file changed outside this settings window.",
                    "File Color Rules Conflict",
                    arrayOf("Reload", "Overwrite", "Cancel"),
                    0,
                    Messages.getWarningIcon(),
                )
            ) {
                0 -> {
                    reset()
                    throw ConfigurationException("Reloaded the external changes. Review them and apply again.")
                }
                1 -> Unit
                else -> throw ConfigurationException("Apply cancelled; no file was changed.")
            }
        }
        try {
            AtomicConfigWriter.write(path, serialized)
        } catch (exception: Exception) {
            throw ConfigurationException(exception.message ?: "Could not write configuration")
        }
        loadedSource = serialized
        loadedFingerprint = AtomicConfigWriter.fingerprint(serialized)
        loadedEnabled = config.options.enabled
        yamlEditor.text = serialized
        service.reloadFromDisk()
        statusLabel.text = "Saved ${path.fileName}"
    }

    override fun reset() {
        val path = service.configPath()
        val source = if (path != null && Files.exists(path)) {
            Files.readString(path, StandardCharsets.UTF_8)
        } else {
            ConfigSerializer.serialize(defaultConfig())
        }
        loadedSource = source
        loadedFingerprint = AtomicConfigWriter.fingerprint(
            if (path != null && Files.exists(path)) Files.readString(path, StandardCharsets.UTF_8) else "",
        )
        yamlEditor.text = source
        val parsed = try {
            ConfigParser.parse(source)
        } catch (_: Exception) {
            service.snapshot().config
        }
        enabledCheckBox.isSelected = parsed.options.enabled
        loadedEnabled = parsed.options.enabled
        refreshVisualModels(parsed)
        statusLabel.text = service.snapshot().problem?.toString() ?: "Configuration is valid"
    }

    override fun disposeUIResources() {
        component = null
    }

    private fun rulesPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JBScrollPane(ruleTable), BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        buttons.add(JButton("Add").apply { addActionListener { addRule() } })
        buttons.add(JButton("Edit").apply { addActionListener { editRule() } })
        buttons.add(JButton("Duplicate").apply { addActionListener { duplicateRule() } })
        buttons.add(JButton("Delete").apply { addActionListener { deleteRule() } })
        buttons.add(JButton("Enable/disable").apply { addActionListener { toggleRule() } })
        buttons.add(JButton("↑").apply { addActionListener { moveRule(-1) } })
        buttons.add(JButton("↓").apply { addActionListener { moveRule(1) } })
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    private fun palettePanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JBScrollPane(colorTable), BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        buttons.add(JButton("Add").apply { addActionListener { addColor() } })
        buttons.add(JButton("Delete").apply { addActionListener { deleteColor() } })
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    private fun fileActionsPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        panel.add(JButton("Open YAML").apply { addActionListener { openYaml() } })
        panel.add(JButton("Reload from disk").apply { addActionListener { reset() } })
        panel.add(JBLabel("Visual changes rewrite YAML canonically; comments may not be preserved."))
        return panel
    }

    private fun onYamlChanged() {
        try {
            val config = ConfigParser.parse(yamlEditor.text)
            RuleCompiler.compile(config, SystemInfoRt.isFileSystemCaseSensitive)
            refreshVisualModels(config)
            statusLabel.text = "Configuration is valid"
        } catch (exception: Exception) {
            statusLabel.text = exception.message ?: "Invalid configuration"
        }
        updateTestResult()
    }

    private fun refreshVisualModels(config: FileColorConfig) {
        ruleTableModel.rules = config.rules
        colorTableModel.colors = config.colors
    }

    private fun currentConfig(): FileColorConfig? = try {
        val parsed = ConfigParser.parse(yamlEditor.text)
        parsed.copy(options = parsed.options.copy(enabled = enabledCheckBox.isSelected))
    } catch (exception: Exception) {
        Messages.showErrorDialog(project, exception.message ?: "Invalid YAML", "File Color Rules")
        null
    }

    private fun setConfig(config: FileColorConfig) {
        yamlEditor.text = ConfigSerializer.serialize(config)
        enabledCheckBox.isSelected = config.options.enabled
        refreshVisualModels(config)
    }

    private fun addRule() {
        val config = currentConfig() ?: return
        if (config.colors.isEmpty()) {
            Messages.showInfoMessage(project, "Create a palette color first.", "File Color Rules")
            return
        }
        val dialog = RuleDialog(project, config, null)
        if (dialog.showAndGet()) setConfig(config.copy(rules = config.rules + dialog.rule()))
    }

    private fun editRule() {
        val config = currentConfig() ?: return
        val index = ruleTable.selectedRow
        if (index !in config.rules.indices) return
        if (config.rules[index].whenCondition is Condition.All ||
            config.rules[index].whenCondition is Condition.Any ||
            config.rules[index].whenCondition is Condition.Not
        ) {
            Messages.showInfoMessage(
                project,
                "Nested condition trees are preserved exactly and can currently be edited in the YAML tab.",
                "File Color Rules",
            )
            return
        }
        val dialog = RuleDialog(project, config, config.rules[index])
        if (dialog.showAndGet()) {
            val rules = config.rules.toMutableList()
            rules[index] = dialog.rule()
            setConfig(config.copy(rules = rules))
        }
    }

    private fun duplicateRule() {
        val config = currentConfig() ?: return
        val index = ruleTable.selectedRow
        if (index !in config.rules.indices) return
        val original = config.rules[index]
        var suffix = 2
        var id = "${original.id}-$suffix"
        while (config.rules.any { it.id == id }) {
            suffix += 1
            id = "${original.id}-$suffix"
        }
        val rules = config.rules.toMutableList()
        rules.add(index + 1, original.copy(id = id, name = "${original.name} copy"))
        setConfig(config.copy(rules = rules))
        ruleTable.setRowSelectionInterval(index + 1, index + 1)
    }

    private fun deleteRule() {
        val config = currentConfig() ?: return
        val index = ruleTable.selectedRow
        if (index !in config.rules.indices) return
        setConfig(config.copy(rules = config.rules.filterIndexed { ruleIndex, _ -> ruleIndex != index }))
    }

    private fun toggleRule() {
        val config = currentConfig() ?: return
        val index = ruleTable.selectedRow
        if (index !in config.rules.indices) return
        val rules = config.rules.toMutableList()
        rules[index] = rules[index].copy(enabled = !rules[index].enabled)
        setConfig(config.copy(rules = rules))
        ruleTable.setRowSelectionInterval(index, index)
    }

    private fun moveRule(offset: Int) {
        val config = currentConfig() ?: return
        val from = ruleTable.selectedRow
        val to = from + offset
        if (from !in config.rules.indices || to !in config.rules.indices) return
        val rules = config.rules.toMutableList()
        val moved = rules.removeAt(from)
        rules.add(to, moved)
        setConfig(config.copy(rules = rules))
        ruleTable.setRowSelectionInterval(to, to)
    }

    private fun addColor() {
        val config = currentConfig() ?: return
        val name = Messages.showInputDialog(project, "Palette color name:", "Add Color", null)?.trim().orEmpty()
        if (!Regex("^[A-Za-z0-9._-]+$").matches(name) || name in config.colors) {
            Messages.showErrorDialog(project, "Enter a new valid color name.", "File Color Rules")
            return
        }
        val light = askHex("Light theme color", "#DCEBFF") ?: return
        val dark = askHex("Dark theme color", "#203A5A") ?: return
        val colors = LinkedHashMap(config.colors)
        colors[name] = PaletteColor(light, dark)
        setConfig(config.copy(colors = colors))
    }

    private fun askHex(label: String, initial: String): String? {
        val value = Messages.showInputDialog(project, "$label (#RRGGBB):", "File Color Rules", null, initial, null)
            ?.trim()?.uppercase() ?: return null
        if (!Regex("^#[0-9A-F]{6}$").matches(value)) {
            Messages.showErrorDialog(project, "Enter a color in #RRGGBB form.", "File Color Rules")
            return null
        }
        return value
    }

    private fun deleteColor() {
        val config = currentConfig() ?: return
        val index = colorTable.selectedRow
        val name = config.colors.keys.elementAtOrNull(index) ?: return
        if (config.rules.any { it.color == name }) {
            Messages.showErrorDialog(project, "Color '$name' is still referenced by a rule.", "File Color Rules")
            return
        }
        val colors = LinkedHashMap(config.colors)
        colors.remove(name)
        setConfig(config.copy(colors = colors))
    }

    private fun updateTestResult() {
        val rawPath = testPathField.text.trim()
        if (rawPath.isEmpty()) {
            testResultLabel.text = " "
            return
        }
        try {
            val config = ConfigParser.parse(yamlEditor.text)
            val compiled = RuleCompiler.compile(config, SystemInfoRt.isFileSystemCaseSensitive)
            val path = ConfigParser.normalizeConfiguredPath(rawPath)
            val name = path.substringAfterLast('/')
            val facts = FileFacts(path, name, name.substringAfterLast('.', ""), rawPath.endsWith('/'))
            val explanation = compiled.explain(facts)
            testResultLabel.text = explanation.result?.let {
                "Winner: ${it.ruleName} (${it.ruleId}) → ${it.colorName}"
            } ?: "No rule matches"
        } catch (exception: Exception) {
            testResultLabel.text = exception.message ?: "Invalid test path"
        }
    }

    private fun openYaml() {
        val path = service.configPath() ?: return
        if (!Files.exists(path)) {
            try {
                AtomicConfigWriter.write(path, ConfigSerializer.serialize(defaultConfig()))
            } catch (exception: Exception) {
                Messages.showErrorDialog(project, exception.message ?: "Could not create YAML", "File Color Rules")
                return
            }
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun defaultConfig(): FileColorConfig = FileColorConfig(
        colors = linkedMapOf(
            "tests" to PaletteColor("#DCEBFF", "#203A5A"),
        ),
        rules = listOf(
            ColorRule(
                id = "tests",
                name = "Tests",
                description = "Test source directories",
                enabled = true,
                color = "tests",
                whenCondition = Condition.Any(
                    listOf(
                        Condition.PathGlob("**/test/**"),
                        Condition.PathGlob("**/tests/**"),
                    ),
                ),
            ),
        ),
    )
}

private class RuleTableModel : AbstractTableModel() {
    var rules: List<ColorRule> = emptyList()
        set(value) {
            field = value
            fireTableDataChanged()
        }
    private val columns = arrayOf("Enabled", "ID", "Name", "Color", "Condition")
    override fun getRowCount(): Int = rules.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) Boolean::class.java else String::class.java
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val rule = rules[rowIndex]
        return when (columnIndex) {
            0 -> rule.enabled
            1 -> rule.id
            2 -> rule.name
            3 -> rule.color
            else -> conditionSummary(rule.whenCondition)
        }
    }
}

private class ColorTableModel : AbstractTableModel() {
    var colors: LinkedHashMap<String, PaletteColor> = linkedMapOf()
        set(value) {
            field = LinkedHashMap(value)
            fireTableDataChanged()
        }
    private val columns = arrayOf("Name", "Light", "Dark", "Contrast note")
    override fun getRowCount(): Int = colors.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val (name, color) = colors.entries.elementAt(rowIndex)
        return when (columnIndex) {
            0 -> name
            1 -> color.light
            2 -> color.dark
            else -> "Check against the active tree background"
        }
    }
}

private class RuleDialog(
    project: Project,
    private val config: FileColorConfig,
    existing: ColorRule?,
) : DialogWrapper(project) {
    private val originalId = existing?.id
    private val idField = JBTextField(existing?.id.orEmpty())
    private val nameField = JBTextField(existing?.name.orEmpty())
    private val descriptionField = JBTextField(existing?.description.orEmpty())
    private val enabled = JBCheckBox("Enabled", existing?.enabled ?: true)
    private val color = JComboBox(config.colors.keys.toTypedArray())
    private val operator = JComboBox(arrayOf("pathGlob", "pathEquals", "underPath", "pathRegex", "nameGlob", "extension", "kind"))
    private val value = JBTextField()

    init {
        title = if (existing == null) "Add File Color Rule" else "Edit File Color Rule"
        existing?.let {
            color.selectedItem = it.color
            val pair = conditionToEditable(it.whenCondition)
            operator.selectedItem = pair.first
            value.text = pair.second
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(4, 4, 4, 4)
        }
        fun row(index: Int, label: String, component: JComponent) {
            constraints.gridy = index
            constraints.gridx = 0
            constraints.weightx = 0.0
            panel.add(JBLabel(label), constraints)
            constraints.gridx = 1
            constraints.weightx = 1.0
            panel.add(component, constraints)
        }
        row(0, "ID", idField)
        row(1, "Name", nameField)
        row(2, "Description", descriptionField)
        row(3, "Color", color)
        row(4, "Condition", operator)
        row(5, "Value", value)
        constraints.gridy = 6
        constraints.gridx = 1
        panel.add(enabled, constraints)
        panel.preferredSize = Dimension(560, 260)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val candidateId = idField.text.trim()
        if (!Regex("^[A-Za-z0-9._-]+$").matches(candidateId)) {
            return ValidationInfo("Enter a valid rule ID.", idField)
        }
        if (candidateId != originalId && config.rules.any { it.id == candidateId }) {
            return ValidationInfo("Rule IDs must be unique.", idField)
        }
        if (nameField.text.isBlank()) return ValidationInfo("Enter a rule name.", nameField)
        if (value.text.isBlank()) return ValidationInfo("Enter a condition value.", value)
        if (operator.selectedItem == "kind" && value.text.trim().lowercase() !in setOf("file", "folder", "any")) {
            return ValidationInfo("Kind must be file, folder, or any.", value)
        }
        return null
    }

    fun rule(): ColorRule = ColorRule(
        id = idField.text.trim(),
        name = nameField.text.trim(),
        description = descriptionField.text.trim().ifEmpty { null },
        enabled = enabled.isSelected,
        color = color.selectedItem as String,
        whenCondition = editableToCondition(operator.selectedItem as String, value.text.trim()),
    )

    private fun conditionToEditable(condition: Condition): Pair<String, String> = when (condition) {
        is Condition.PathGlob -> "pathGlob" to condition.value
        is Condition.PathEquals -> "pathEquals" to condition.value
        is Condition.UnderPath -> "underPath" to condition.value
        is Condition.PathRegex -> "pathRegex" to condition.value
        is Condition.NameGlob -> "nameGlob" to condition.value
        is Condition.Extension -> "extension" to condition.value
        is Condition.Kind -> "kind" to condition.value.name.lowercase()
        else -> error("Nested conditions are edited in the YAML tab")
    }

    private fun editableToCondition(operator: String, value: String): Condition = when (operator) {
        "pathEquals" -> Condition.PathEquals(ConfigParser.normalizeConfiguredPath(value))
        "underPath" -> Condition.UnderPath(ConfigParser.normalizeConfiguredPath(value))
        "pathRegex" -> Condition.PathRegex(value)
        "nameGlob" -> Condition.NameGlob(value)
        "extension" -> Condition.Extension(value.removePrefix("."))
        "kind" -> Condition.Kind(FileKind.valueOf(value.uppercase()))
        else -> Condition.PathGlob(value)
    }
}

private fun conditionSummary(condition: Condition): String = when (condition) {
    is Condition.All -> "all(${condition.conditions.size})"
    is Condition.Any -> "any(${condition.conditions.size})"
    is Condition.Not -> "not ${conditionSummary(condition.condition)}"
    is Condition.PathEquals -> "path = ${condition.value}"
    is Condition.UnderPath -> "under ${condition.value}"
    is Condition.PathGlob -> "path glob ${condition.value}"
    is Condition.PathRegex -> "path regex ${condition.value}"
    is Condition.NameGlob -> "name glob ${condition.value}"
    is Condition.Extension -> "extension ${condition.value}"
    is Condition.Kind -> "kind ${condition.value.name.lowercase()}"
}
