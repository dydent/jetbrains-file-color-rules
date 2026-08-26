package dev.dydent.filecolorrules.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException

object ConfigParser {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_RULES = 1_000
    const val MAX_DEPTH = 32
    const val MAX_CONDITION_NODES = 10_000

    private val colorPattern = Regex("^#[0-9A-Fa-f]{6}$")
    private val settings = LoadSettings.builder().setAllowDuplicateKeys(false).build()

    fun parse(source: String): FileColorConfig {
        if (source.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            fail("Configuration exceeds the 1 MiB limit")
        }

        val loaded = try {
            Load(settings).loadFromString(source)
        } catch (exception: MarkedYamlEngineException) {
            val mark = exception.problemMark.orElse(null)
            throw ConfigException(
                ConfigProblem(
                    exception.problem ?: "Invalid YAML",
                    (mark?.line ?: 0) + 1,
                    (mark?.column ?: 0) + 1,
                ),
            )
        } catch (exception: Exception) {
            throw ConfigException(ConfigProblem(exception.message ?: "Invalid YAML"))
        }

        if (loaded == null) return FileColorConfig.empty()
        val root = loaded.asStringMap("root")
        root.requireOnly("version", "options", "colors", "rules")
        val version = root.requiredInt("version")
        if (version != 1) fail("Unsupported version '$version'; expected 1")

        val options = parseOptions(root["options"])
        val colors = parseColors(root["colors"])
        val rawRules = root["rules"]?.asList("rules") ?: emptyList()
        if (rawRules.size > MAX_RULES) fail("Configuration exceeds the 1,000 rule limit")

        var conditionNodes = 0
        val rules = rawRules.mapIndexed { index, value ->
            val map = value.asStringMap("rules[$index]")
            map.requireOnly("id", "name", "description", "enabled", "color", "when")
            val condition = parseCondition(map.required("when"), 1) {
                conditionNodes += 1
                if (conditionNodes > MAX_CONDITION_NODES) {
                    fail("Configuration exceeds the 10,000 condition node limit")
                }
            }
            ColorRule(
                id = map.requiredString("id").also { requireIdentifier(it, "rule ID") },
                name = map.requiredString("name").also { requireNonBlank(it, "rule name") },
                description = map.optionalString("description"),
                enabled = map.requiredBoolean("enabled"),
                color = map.requiredString("color"),
                whenCondition = condition,
            )
        }

        val duplicateId = rules.groupingBy { it.id }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicateId != null) fail("Duplicate rule ID '$duplicateId'")
        rules.firstOrNull { it.color !in colors }?.let {
            fail("Rule '${it.id}' references unknown color '${it.color}'")
        }
        return FileColorConfig(version, options, colors, rules)
    }

    private fun parseOptions(value: Any?): ConfigOptions {
        if (value == null) return ConfigOptions()
        val map = value.asStringMap("options")
        map.requireOnly("enabled", "caseSensitivity")
        val enabled = map["enabled"]?.asBoolean("options.enabled") ?: true
        val sensitivity = when (map["caseSensitivity"]?.asString("options.caseSensitivity") ?: "auto") {
            "auto" -> CaseSensitivity.AUTO
            "sensitive" -> CaseSensitivity.SENSITIVE
            "insensitive" -> CaseSensitivity.INSENSITIVE
            else -> fail("options.caseSensitivity must be auto, sensitive, or insensitive")
        }
        return ConfigOptions(enabled, sensitivity)
    }

    private fun parseColors(value: Any?): LinkedHashMap<String, PaletteColor> {
        val result = linkedMapOf<String, PaletteColor>()
        if (value == null) return result
        value.asStringMap("colors").forEach { (name, rawColor) ->
            requireIdentifier(name, "color name")
            val color = when (rawColor) {
                is String -> PaletteColor(validateColor(rawColor), validateColor(rawColor))
                else -> {
                    val map = rawColor.asStringMap("colors.$name")
                    map.requireOnly("light", "dark")
                    PaletteColor(
                        validateColor(map.requiredString("light")),
                        validateColor(map.requiredString("dark")),
                    )
                }
            }
            result[name] = color
        }
        return result
    }

    private fun parseCondition(value: Any?, depth: Int, onNode: () -> Unit): Condition {
        if (depth > MAX_DEPTH) fail("Condition nesting exceeds the 32 level limit")
        onNode()
        val map = value.asStringMap("condition")
        if (map.size != 1) fail("Each condition must contain exactly one operator")
        val (operator, operand) = map.entries.single()
        return when (operator) {
            "all" -> Condition.All(nonEmptyConditions(operand, operator, depth, onNode))
            "any" -> Condition.Any(nonEmptyConditions(operand, operator, depth, onNode))
            "not" -> Condition.Not(parseCondition(operand, depth + 1, onNode))
            "pathEquals" -> Condition.PathEquals(normalizeConfiguredPath(operand.asString(operator)))
            "underPath" -> Condition.UnderPath(normalizeConfiguredPath(operand.asString(operator)))
            "pathGlob" -> Condition.PathGlob(requireNonBlank(operand.asString(operator), operator))
            "pathRegex" -> Condition.PathRegex(requireNonBlank(operand.asString(operator), operator))
            "nameGlob" -> Condition.NameGlob(requireNonBlank(operand.asString(operator), operator))
            "extension" -> Condition.Extension(operand.asString(operator).removePrefix("."))
            "kind" -> Condition.Kind(
                when (operand.asString(operator)) {
                    "file" -> FileKind.FILE
                    "folder" -> FileKind.FOLDER
                    "any" -> FileKind.ANY
                    else -> fail("kind must be file, folder, or any")
                },
            )
            else -> fail("Unknown condition operator '$operator'")
        }
    }

    private fun nonEmptyConditions(
        value: Any?,
        operator: String,
        depth: Int,
        onNode: () -> Unit,
    ): List<Condition> {
        val list = value.asList(operator)
        if (list.isEmpty()) fail("$operator must contain at least one condition")
        return list.map { parseCondition(it, depth + 1, onNode) }
    }

    fun normalizeConfiguredPath(value: String): String {
        if ('\u0000' in value) fail("Paths cannot contain NUL characters")
        val slashPath = value.replace('\\', '/').trimEnd('/')
        if (slashPath.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(slashPath)) {
            fail("Paths must be relative to the project root")
        }
        val parts = slashPath.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) fail("Paths cannot contain '..' traversal")
        return parts.joinToString("/").ifEmpty { "." }
    }

    private fun validateColor(value: String): String {
        if (!colorPattern.matches(value)) fail("Invalid color '$value'; expected #RRGGBB")
        return value.uppercase()
    }

    private fun requireIdentifier(value: String, label: String) {
        requireNonBlank(value, label)
        if (!Regex("^[A-Za-z0-9._-]+$").matches(value)) {
            fail("$label '$value' may contain only letters, digits, dot, underscore, and hyphen")
        }
    }

    private fun requireNonBlank(value: String, label: String): String {
        if (value.isBlank()) fail("$label cannot be blank")
        return value
    }

    private fun Map<String, Any?>.requireOnly(vararg keys: String) {
        val unknown = this.keys - keys.toSet()
        if (unknown.isNotEmpty()) fail("Unknown key '${unknown.first()}'")
    }

    private fun Map<String, Any?>.required(key: String): Any =
        this[key] ?: fail("Missing required key '$key'")

    private fun Map<String, Any?>.requiredString(key: String): String = required(key).asString(key)
    private fun Map<String, Any?>.optionalString(key: String): String? = this[key]?.asString(key)
    private fun Map<String, Any?>.requiredBoolean(key: String): Boolean = required(key).asBoolean(key)

    private fun Map<String, Any?>.requiredInt(key: String): Int {
        val value = required(key)
        return (value as? Number)?.toInt() ?: fail("$key must be an integer")
    }

    private fun Any?.asString(label: String): String = this as? String ?: fail("$label must be a string")
    private fun Any?.asBoolean(label: String): Boolean = this as? Boolean ?: fail("$label must be true or false")
    private fun Any?.asList(label: String): List<Any?> = this as? List<Any?> ?: fail("$label must be a list")

    private fun Any?.asStringMap(label: String): LinkedHashMap<String, Any?> {
        val source = this as? Map<*, *> ?: fail("$label must be a mapping")
        val result = linkedMapOf<String, Any?>()
        source.forEach { (key, value) ->
            val stringKey = key as? String ?: fail("$label contains a non-string key")
            result[stringKey] = value
        }
        return result
    }

    private fun fail(message: String): Nothing = throw ConfigException(ConfigProblem(message))
}
