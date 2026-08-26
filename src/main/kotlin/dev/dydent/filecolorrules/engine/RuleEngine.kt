package dev.dydent.filecolorrules.engine

import com.google.re2j.Pattern
import dev.dydent.filecolorrules.config.CaseSensitivity
import dev.dydent.filecolorrules.config.ColorRule
import dev.dydent.filecolorrules.config.Condition
import dev.dydent.filecolorrules.config.ConfigException
import dev.dydent.filecolorrules.config.ConfigProblem
import dev.dydent.filecolorrules.config.FileColorConfig
import dev.dydent.filecolorrules.config.FileKind
import dev.dydent.filecolorrules.config.PaletteColor

data class FileFacts(
    val path: String,
    val name: String,
    val extension: String,
    val isDirectory: Boolean,
)

data class MatchResult(
    val ruleId: String,
    val ruleName: String,
    val colorName: String,
    val color: PaletteColor,
)

data class RuleExplanation(
    val result: MatchResult?,
    val evaluatedRules: List<Pair<String, Boolean>>,
)

class CompiledConfig internal constructor(
    val source: FileColorConfig,
    private val enabled: Boolean,
    private val rules: List<CompiledRule>,
) {
    fun match(facts: FileFacts): MatchResult? {
        if (!enabled) return null
        for (rule in rules) {
            if (rule.matches(facts)) return rule.result
        }
        return null
    }

    fun explain(facts: FileFacts): RuleExplanation {
        if (!enabled) return RuleExplanation(null, emptyList())
        val evaluated = mutableListOf<Pair<String, Boolean>>()
        for (rule in rules) {
            val matches = rule.matches(facts)
            evaluated += rule.result.ruleId to matches
            if (matches) return RuleExplanation(rule.result, evaluated)
        }
        return RuleExplanation(null, evaluated)
    }

    companion object {
        val EMPTY = CompiledConfig(FileColorConfig.empty(), false, emptyList())
    }
}

internal data class CompiledRule(
    val result: MatchResult,
    val matches: (FileFacts) -> Boolean,
)

object RuleCompiler {
    fun compile(config: FileColorConfig, fileSystemCaseSensitive: Boolean): CompiledConfig {
        val caseSensitive = when (config.options.caseSensitivity) {
            CaseSensitivity.AUTO -> fileSystemCaseSensitive
            CaseSensitivity.SENSITIVE -> true
            CaseSensitivity.INSENSITIVE -> false
        }
        val compiled = config.rules.asSequence()
            .filter(ColorRule::enabled)
            .map { rule ->
                val color = config.colors[rule.color]
                    ?: throw ConfigException(ConfigProblem("Rule '${rule.id}' references unknown color '${rule.color}'"))
                CompiledRule(
                    MatchResult(rule.id, rule.name, rule.color, color),
                    compileCondition(rule.whenCondition, caseSensitive),
                )
            }
            .toList()
        return CompiledConfig(config, config.options.enabled, compiled)
    }

    private fun compileCondition(condition: Condition, caseSensitive: Boolean): (FileFacts) -> Boolean =
        when (condition) {
            is Condition.All -> {
                val children = condition.conditions.map { compileCondition(it, caseSensitive) }
                val predicate: (FileFacts) -> Boolean = { facts -> children.all { it(facts) } }
                predicate
            }
            is Condition.Any -> {
                val children = condition.conditions.map { compileCondition(it, caseSensitive) }
                val predicate: (FileFacts) -> Boolean = { facts -> children.any { it(facts) } }
                predicate
            }
            is Condition.Not -> {
                val child = compileCondition(condition.condition, caseSensitive)
                val predicate: (FileFacts) -> Boolean = { facts -> !child(facts) }
                predicate
            }
            is Condition.PathEquals -> {
                val expected = fold(condition.value, caseSensitive)
                val predicate: (FileFacts) -> Boolean = { facts -> fold(facts.path, caseSensitive) == expected }
                predicate
            }
            is Condition.UnderPath -> {
                val expected = fold(condition.value, caseSensitive)
                val predicate: (FileFacts) -> Boolean = { facts ->
                    val path = fold(facts.path, caseSensitive)
                    expected == "." || path == expected || path.startsWith("$expected/")
                }
                predicate
            }
            is Condition.PathGlob -> {
                val pattern = GlobCompiler.compile(condition.value, caseSensitive)
                val predicate: (FileFacts) -> Boolean = { facts -> pattern.matcher(facts.path).matches() }
                predicate
            }
            is Condition.PathRegex -> {
                val pattern = compileRegex(condition.value, caseSensitive)
                val predicate: (FileFacts) -> Boolean = { facts -> pattern.matcher(facts.path).matches() }
                predicate
            }
            is Condition.NameGlob -> {
                val pattern = GlobCompiler.compile(condition.value, caseSensitive, pathMode = false)
                val predicate: (FileFacts) -> Boolean = { facts -> pattern.matcher(facts.name).matches() }
                predicate
            }
            is Condition.Extension -> {
                val expected = fold(condition.value.removePrefix("."), caseSensitive)
                val predicate: (FileFacts) -> Boolean = { facts -> fold(facts.extension, caseSensitive) == expected }
                predicate
            }
            is Condition.Kind -> when (condition.value) {
                FileKind.FILE -> { facts -> !facts.isDirectory }
                FileKind.FOLDER -> { facts -> facts.isDirectory }
                FileKind.ANY -> { _ -> true }
            }
        }

    private fun compileRegex(source: String, caseSensitive: Boolean): Pattern = try {
        Pattern.compile(if (caseSensitive) source else "(?i)$source")
    } catch (exception: RuntimeException) {
        throw ConfigException(ConfigProblem("Invalid RE2/J expression '$source': ${exception.message}"))
    }

    private fun fold(value: String, caseSensitive: Boolean): String =
        if (caseSensitive) value else value.lowercase()
}

object GlobCompiler {
    fun compile(glob: String, caseSensitive: Boolean, pathMode: Boolean = true): Pattern {
        if (glob.isEmpty()) throw ConfigException(ConfigProblem("Glob cannot be empty"))
        val regex = buildString {
            append('^')
            var index = 0
            while (index < glob.length) {
                val character = glob[index]
                when {
                    character == '\\' -> {
                        index += 1
                        if (index >= glob.length) {
                            throw ConfigException(ConfigProblem("Glob cannot end with an escape"))
                        }
                        appendEscaped(glob[index])
                    }
                    character == '*' && index + 1 < glob.length && glob[index + 1] == '*' -> {
                        index += 1
                        if (pathMode && index + 1 < glob.length && glob[index + 1] == '/') {
                            index += 1
                            append("(?:.*/)?")
                        } else {
                            append(if (pathMode) ".*" else ".*")
                        }
                    }
                    character == '*' -> append(if (pathMode) "[^/]*" else ".*")
                    character == '?' -> append(if (pathMode) "[^/]" else ".")
                    else -> appendEscaped(character)
                }
                index += 1
            }
            append('$')
        }
        return try {
            Pattern.compile(if (caseSensitive) regex else "(?i)$regex")
        } catch (exception: RuntimeException) {
            throw ConfigException(ConfigProblem("Invalid glob '$glob': ${exception.message}"))
        }
    }

    private fun StringBuilder.appendEscaped(character: Char) {
        if (character in "\\.^$|()[]{}+") append('\\')
        append(character)
    }
}
