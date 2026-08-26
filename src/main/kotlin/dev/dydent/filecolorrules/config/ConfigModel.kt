package dev.dydent.filecolorrules.config

const val CONFIG_FILE_NAME = ".jetbrains-file-colors.yaml"

enum class CaseSensitivity { AUTO, SENSITIVE, INSENSITIVE }

enum class FileKind { FILE, FOLDER, ANY }

data class ConfigOptions(
    val enabled: Boolean = true,
    val caseSensitivity: CaseSensitivity = CaseSensitivity.AUTO,
)

data class PaletteColor(
    val light: String,
    val dark: String,
)

sealed interface Condition {
    data class All(val conditions: List<Condition>) : Condition
    data class Any(val conditions: List<Condition>) : Condition
    data class Not(val condition: Condition) : Condition
    data class PathEquals(val value: String) : Condition
    data class UnderPath(val value: String) : Condition
    data class PathGlob(val value: String) : Condition
    data class PathRegex(val value: String) : Condition
    data class NameGlob(val value: String) : Condition
    data class Extension(val value: String) : Condition
    data class Kind(val value: FileKind) : Condition
}

data class ColorRule(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean,
    val color: String,
    val whenCondition: Condition,
)

data class FileColorConfig(
    val version: Int = 1,
    val options: ConfigOptions = ConfigOptions(),
    val colors: LinkedHashMap<String, PaletteColor> = linkedMapOf(),
    val rules: List<ColorRule> = emptyList(),
) {
    companion object {
        fun empty(): FileColorConfig = FileColorConfig()
    }
}

data class ConfigProblem(
    val message: String,
    val line: Int = 1,
    val column: Int = 1,
) {
    override fun toString(): String = "$message (line $line, column $column)"
}

class ConfigException(val problem: ConfigProblem) : IllegalArgumentException(problem.toString())
