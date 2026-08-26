package dev.dydent.filecolorrules.config

object ConfigSerializer {
    fun serialize(config: FileColorConfig): String = buildString {
        appendLine("version: 1")
        appendLine()
        appendLine("options:")
        appendLine("  enabled: ${config.options.enabled}")
        appendLine("  caseSensitivity: ${config.options.caseSensitivity.name.lowercase()}")
        appendLine()
        appendLine("colors:")
        config.colors.forEach { (name, color) ->
            if (color.light == color.dark) {
                appendLine("  ${quote(name)}: ${quote(color.light)}")
            } else {
                appendLine("  ${quote(name)}:")
                appendLine("    light: ${quote(color.light)}")
                appendLine("    dark: ${quote(color.dark)}")
            }
        }
        appendLine()
        appendLine("rules:")
        config.rules.forEach { rule ->
            appendLine("  - id: ${quote(rule.id)}")
            appendLine("    name: ${quote(rule.name)}")
            rule.description?.let { appendLine("    description: ${quote(it)}") }
            appendLine("    enabled: ${rule.enabled}")
            appendLine("    color: ${quote(rule.color)}")
            appendLine("    when:")
            appendCondition(rule.whenCondition, 6)
        }
    }

    private fun StringBuilder.appendCondition(condition: Condition, indent: Int) {
        val prefix = " ".repeat(indent)
        when (condition) {
            is Condition.All -> {
                appendLine("${prefix}all:")
                condition.conditions.forEach { child ->
                    append("${prefix}  - ")
                    appendConditionAfterDash(child, indent + 4)
                }
            }
            is Condition.Any -> {
                appendLine("${prefix}any:")
                condition.conditions.forEach { child ->
                    append("${prefix}  - ")
                    appendConditionAfterDash(child, indent + 4)
                }
            }
            is Condition.Not -> {
                appendLine("${prefix}not:")
                appendCondition(condition.condition, indent + 2)
            }
            is Condition.PathEquals -> appendLine("${prefix}pathEquals: ${quote(condition.value)}")
            is Condition.UnderPath -> appendLine("${prefix}underPath: ${quote(condition.value)}")
            is Condition.PathGlob -> appendLine("${prefix}pathGlob: ${quote(condition.value)}")
            is Condition.PathRegex -> appendLine("${prefix}pathRegex: ${quote(condition.value)}")
            is Condition.NameGlob -> appendLine("${prefix}nameGlob: ${quote(condition.value)}")
            is Condition.Extension -> appendLine("${prefix}extension: ${quote(condition.value)}")
            is Condition.Kind -> appendLine("${prefix}kind: ${condition.value.name.lowercase()}")
        }
    }

    private fun StringBuilder.appendConditionAfterDash(condition: Condition, childIndent: Int) {
        when (condition) {
            is Condition.All -> {
                appendLine("all:")
                condition.conditions.forEach { child ->
                    append("${" ".repeat(childIndent)}- ")
                    appendConditionAfterDash(child, childIndent + 2)
                }
            }
            is Condition.Any -> {
                appendLine("any:")
                condition.conditions.forEach { child ->
                    append("${" ".repeat(childIndent)}- ")
                    appendConditionAfterDash(child, childIndent + 2)
                }
            }
            is Condition.Not -> {
                appendLine("not:")
                appendCondition(condition.condition, childIndent)
            }
            is Condition.PathEquals -> appendLine("pathEquals: ${quote(condition.value)}")
            is Condition.UnderPath -> appendLine("underPath: ${quote(condition.value)}")
            is Condition.PathGlob -> appendLine("pathGlob: ${quote(condition.value)}")
            is Condition.PathRegex -> appendLine("pathRegex: ${quote(condition.value)}")
            is Condition.NameGlob -> appendLine("nameGlob: ${quote(condition.value)}")
            is Condition.Extension -> appendLine("extension: ${quote(condition.value)}")
            is Condition.Kind -> appendLine("kind: ${condition.value.name.lowercase()}")
        }
    }

    private fun quote(value: String): String = "'${value.replace("'", "''")}'"
}
