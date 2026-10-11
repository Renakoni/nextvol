package indi.renakoni.nextvol.data.content.component

import android.content.Context
import indi.renakoni.nextvol.utils.ofId
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.dom4j.DocumentHelper
import org.dom4j.Element

@Serializable
data class ErrorContentComponentData(
    val message: String
): AbstractContentComponentData() {
    override val id = Companion.id
    override fun toJsonElement(): JsonElement = Json.encodeToJsonElement(this)
    override fun toHtmlElement(context: Context): Element = DocumentHelper.createElement("div")

    companion object {
        val id = "error_content_component".ofId()
    }
}
