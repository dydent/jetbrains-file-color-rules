package dev.dydent.filecolorrules.engine

import dev.dydent.filecolorrules.config.CaseSensitivity
import dev.dydent.filecolorrules.config.ColorRule
import dev.dydent.filecolorrules.config.Condition
import dev.dydent.filecolorrules.config.ConfigOptions
import dev.dydent.filecolorrules.config.FileColorConfig
import dev.dydent.filecolorrules.config.PaletteColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEngineTest {
    @Test
    fun globSupportsRootAndNestedDoubleStar() {
        val pattern = GlobCompiler.compile("**/test/**", caseSensitive = true)
        assertTrue(pattern.matcher("test/Foo.kt").matches())
        assertTrue(pattern.matcher("src/test/Foo.kt").matches())
        assertFalse(pattern.matcher("src/testing/Foo.kt").matches())
    }

    @Test
    fun firstEnabledMatchWins() {
        val config = FileColorConfig(
            colors = linkedMapOf(
                "first" to PaletteColor("#111111", "#222222"),
                "second" to PaletteColor("#333333", "#444444"),
            ),
            rules = listOf(
                rule("first-rule", "first", Condition.PathGlob("**/*.kt")),
                rule("second-rule", "second", Condition.Extension("kt")),
            ),
        )
        val result = RuleCompiler.compile(config, true)
            .match(FileFacts("src/Main.kt", "Main.kt", "kt", false))
        assertNotNull(result)
        assertEquals("first-rule", result.ruleId)
    }

    @Test
    fun nestedLogicAndCaseSensitivityWork() {
        val config = FileColorConfig(
            options = ConfigOptions(caseSensitivity = CaseSensitivity.INSENSITIVE),
            colors = linkedMapOf("tests" to PaletteColor("#111111", "#222222")),
            rules = listOf(
                rule(
                    "tests",
                    "tests",
                    Condition.All(
                        listOf(
                            Condition.UnderPath("SRC"),
                            Condition.Not(Condition.Extension("md")),
                        ),
                    ),
                ),
            ),
        )
        val compiled = RuleCompiler.compile(config, fileSystemCaseSensitive = true)
        assertNotNull(compiled.match(FileFacts("src/Main.KT", "Main.KT", "KT", false)))
        assertNull(compiled.match(FileFacts("src/readme.MD", "readme.MD", "MD", false)))
    }

    @Test
    fun disabledConfigurationMatchesNothing() {
        val config = FileColorConfig(
            options = ConfigOptions(enabled = false),
            colors = linkedMapOf("all" to PaletteColor("#111111", "#222222")),
            rules = listOf(rule("all", "all", Condition.PathGlob("**"))),
        )
        assertNull(RuleCompiler.compile(config, true).match(FileFacts("a.txt", "a.txt", "txt", false)))
    }

    private fun rule(id: String, color: String, condition: Condition) =
        ColorRule(id, id, enabled = true, color = color, whenCondition = condition)
}
