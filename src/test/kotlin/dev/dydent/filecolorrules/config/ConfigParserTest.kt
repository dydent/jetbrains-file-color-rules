package dev.dydent.filecolorrules.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigParserTest {
    @Test
    fun parsesCompleteSchema() {
        val config = ConfigParser.parse(sample)
        assertEquals(1, config.version)
        assertEquals(CaseSensitivity.AUTO, config.options.caseSensitivity)
        assertEquals(PaletteColor("#DCEBFF", "#203A5A"), config.colors["tests"])
        assertEquals("tests", config.rules.single().id)
        assertTrue(config.rules.single().whenCondition is Condition.Any)
    }

    @Test
    fun rejectsDuplicateIds() {
        val duplicate = sample.replace(
            "rules:",
            """rules:
  - id: tests
    name: Duplicate
    enabled: true
    color: tests
    when:
      kind: file""",
        )
        assertFailsWith<ConfigException> { ConfigParser.parse(duplicate) }
    }

    @Test
    fun rejectsUnknownKeysAndTraversal() {
        assertFailsWith<ConfigException> { ConfigParser.parse(sample.replace("version: 1", "version: 1\nmystery: true")) }
        assertFailsWith<ConfigException> { ConfigParser.normalizeConfiguredPath("../secret") }
    }

    @Test
    fun serializerRoundTripsNestedConditions() {
        val parsed = ConfigParser.parse(sample)
        val roundTripped = ConfigParser.parse(ConfigSerializer.serialize(parsed))
        assertEquals(parsed, roundTripped)
    }

    private val sample = """
        version: 1
        options:
          enabled: true
          caseSensitivity: auto
        colors:
          tests:
            light: "#DCEBFF"
            dark: "#203A5A"
        rules:
          - id: tests
            name: Tests
            description: Test sources
            enabled: true
            color: tests
            when:
              any:
                - pathGlob: "**/test/**"
                - pathGlob: "**/tests/**"
    """.trimIndent()
}
