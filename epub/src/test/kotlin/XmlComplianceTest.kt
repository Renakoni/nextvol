import io.nightfish.potatoepub.builder.SimpleContentBuilder
import io.nightfish.potatoepub.xml.asFormatedXml
import io.nightfish.potatoepub.xml.sanitizeXmlSimple
import org.dom4j.DocumentHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class XmlComplianceTest {
    @Test
    fun xmlCharactersAreFilteredByCodePoint() {
        val legal = "\t\n\r &<> \u0020\uD7FF\uE000\uFFFD \uD83D\uDE00 \uD840\uDC00 \uDBFF\uDFFF"
        assertEquals(legal + "x", sanitizeXmlSimple("\u0000\u0008\u000B\u001F" + legal + "\uD800x\uDC00\uFFFE\uFFFF"))
        assertEquals("&#9;&#10;&#13;&#x1F600;&#128512;&#x10FFFF;",
            sanitizeXmlSimple("&#0;&#xD800;&#9;&#10;&#13;&#x1F600;&#128512;&#x10FFFF;&#x110000;&#999999999999999999999;"))
    }

    @Test
    fun serializationPreservesMixedContentWhitespaceAndLiteralReferences() {
        val expected = "  A & <B> \uD83D\uDE00\uD840\uDC00\t&#0; &#xFF;  "
        val builder = SimpleContentBuilder().apply {
            title("A & B")
            text(expected + "\u0001")
            br()
            text(" after ")
        }
        val parsed = DocumentHelper.parseText(builder.build().asFormatedXml())
        assertEquals(expected + " after ", parsed.rootElement.element("body").element("div").stringValue)
        assertEquals(1, parsed.rootElement.element("body").element("div").elements("br").size)
    }

    @Test
    fun extensionHtmlIsQualifiedWithoutRewritingForeignNamespaces() {
        val component = DocumentHelper.createElement("div")
        val paragraph = component.addElement("p").addText("Extension text")
        val svg = component.addElement("svg", "http://www.w3.org/2000/svg")
        val circle = svg.addElement("circle")
        val math = component.addElement("math", "http://www.w3.org/1998/Math/MathML")
        val identifier = math.addElement("mi").addText("x")
        SimpleContentBuilder().addContent(component)
        assertEquals("http://www.w3.org/1999/xhtml", component.namespaceURI)
        assertEquals(component.namespaceURI, paragraph.namespaceURI)
        assertEquals("http://www.w3.org/2000/svg", circle.namespaceURI)
        assertEquals("http://www.w3.org/1998/Math/MathML", identifier.namespaceURI)
        assertSame(component, paragraph.parent)
    }
}
