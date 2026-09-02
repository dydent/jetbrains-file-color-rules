package dev.dydent.filecolorrules

import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class PluginDescriptorTest {
    @Test
    fun `project view action group has a visible label`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(Path.of("src/main/resources/META-INF/plugin.xml").toFile())
        val groups = document.getElementsByTagName("group")
        val group = (0 until groups.length)
            .map { groups.item(it) }
            .first { it.attributes.getNamedItem("id")?.nodeValue == "dev.dydent.filecolorrules.projectViewGroup" }

        assertEquals("File Color Rules", group.attributes.getNamedItem("text")?.nodeValue)
    }
}
