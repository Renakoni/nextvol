package hnovel.rhino

import org.jsoup.Jsoup
import org.jsoup.nodes.*
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import org.jsoup.parser.Tag
import org.jsoup.select.Elements
import org.mozilla.javascript.*
import kotlinx.serialization.json.*
import java.lang.reflect.Modifier
import java.nio.charset.Charset

/** Only these worker-owned data types and named methods participate in DOM dispatch. */
internal open class ScriptDomValue(val value: Any) : NativeObject()
internal class ScriptDomElement(val element: Element) : ScriptDomValue(element)

internal object ScriptDom {
    private fun names(value: String) = value.split(' ').filter(String::isNotEmpty).toSet()
    private val nodeMethods = names("nodeName normalName hasParent attributesSize sourceRange attr attributes hasAttr removeAttr clearAttributes baseUri setBaseUri absUrl childNode childNodes childNodesCopy childNodeSize empty parent parentNode root ownerDocument remove before after wrap unwrap replaceWith siblingNodes nextSibling previousSibling siblingIndex firstChild lastChild outerHtml toString equals hashCode hasSameValue clone shallowClone")
    private val elementMethods = nodeMethods + names("endSourceRange tagName normalName tag isBlock id dataset parents child childrenSize children textNodes dataNodes select selectFirst expectFirst is closest selectXpath appendChild appendChildren appendTo prependChild prependChildren insertChildren appendElement prependElement appendText prependText append prepend cssSelector siblingElements nextElementSibling nextElementSiblings previousElementSibling previousElementSiblings firstElementSibling elementSiblingIndex lastElementSibling firstElementChild lastElementChild getElementsByTag getElementById getElementsByClass getElementsByAttribute getElementsByAttributeStarting getElementsByAttributeValue getElementsByAttributeValueNot getElementsByAttributeValueStarting getElementsByAttributeValueEnding getElementsByAttributeValueContaining getElementsByAttributeValueMatching getElementsByIndexLessThan getElementsByIndexGreaterThan getElementsByIndexEquals getElementsContainingText getElementsContainingOwnText getElementsMatchingText getElementsMatchingOwnText getAllElements text wholeText wholeOwnText ownText hasText data className classNames hasClass addClass removeClass toggleClass val html")
    private val exports = linkedMapOf<Class<*>, Set<String>>(
        org.jsoup.Connection.Response::class.java to names("statusCode statusMessage charset contentType parse body bodyAsBytes bufferUp bodyStream url method header headers addHeader hasHeader hasHeaderWithValue removeHeader multiHeaders cookie hasCookie removeCookie cookies"),
        okhttp3.Response::class.java to names("code message headers header body isSuccessful isRedirect request protocol sentRequestAtMillis receivedResponseAtMillis close toString"),
        okhttp3.ResponseBody::class.java to names("contentType contentLength byteStream bytes byteString source charStream string close"),
        okhttp3.Headers::class.java to names("get values names size name value toMultimap byteCount toString"),
        okhttp3.Request::class.java to names("url method isHttps toString"),
        okhttp3.HttpUrl::class.java to names("scheme host port username password encodedUsername encodedPassword pathSegments encodedPathSegments encodedPath query encodedQuery querySize queryParameter queryParameterNames queryParameterValues queryParameterName queryParameterValue fragment encodedFragment isHttps resolve redact url toString"),
        okhttp3.MediaType::class.java to names("type subtype charset parameter toString"),
        okio.BufferedSource::class.java to names("buffer getBuffer exhausted require request readByte readShort readShortLe readInt readIntLe readLong readLongLe readDecimalLong readHexadecimalUnsignedLong skip readByteString readByteArray readUtf8 readUtf8Line readUtf8LineStrict readUtf8CodePoint readString indexOf indexOfElement rangeEquals inputStream peek close"),
        okio.ByteString::class.java to names("utf8 string base64 base64Url hex size getByte toByteArray substring startsWith endsWith indexOf lastIndexOf toAsciiLowercase toAsciiUppercase toString"),
        Document::class.java to (elementMethods + names("location documentType head body forms expectForm title createElement charset updateMetaCharsetElement outputSettings quirksMode parser")),
        org.jsoup.parser.Parser::class.java to names("parseInput parseFragmentInput getErrors isTrackErrors setTrackErrors isTrackPosition setTrackPosition settings isContentForTagData clone"),
        org.jsoup.parser.ParseSettings::class.java to names("preserveTagCase preserveAttributeCase normalizeTag normalizeAttribute"),
        org.jsoup.parser.ParseError::class.java to names("getErrorMessage getPosition getCursorPos toString"),
        Range::class.java to names("start end isTracked toString equals hashCode"),
        Range.Position::class.java to names("pos lineNumber columnNumber isTracked toString equals hashCode"),
        FormElement::class.java to (elementMethods + names("elements addElement formData")),
        org.jsoup.Connection.KeyVal::class.java to names("key value inputStream hasInputStream contentType toString"),
        Element::class.java to elementMethods,
        TextNode::class.java to (nodeMethods + names("text getWholeText isBlank splitText")),
        DataNode::class.java to (nodeMethods + names("getWholeData setWholeData")),
        Comment::class.java to (nodeMethods + names("getData setData isXmlDeclaration asXmlDeclaration")),
        XmlDeclaration::class.java to (nodeMethods + names("name getWholeDeclaration")),
        DocumentType::class.java to (nodeMethods + names("name publicId systemId setPubSysKey")),
        Node::class.java to nodeMethods,
        Elements::class.java to names("clone attr hasAttr eachAttr removeAttr addClass removeClass toggleClass hasClass val text hasText eachText html outerHtml toString tagName prepend append before after wrap unwrap empty remove select not eq is next nextAll prev prevAll parents first last forms comments textNodes dataNodes get size isEmpty add addAll set clear contains containsAll indexOf lastIndexOf removeAll retainAll subList toArray"),
        Attributes::class.java to names("get getIgnoreCase add put remove removeIgnoreCase hasKey hasKeyIgnoreCase hasDeclaredValueForKey hasDeclaredValueForKeyIgnoreCase size isEmpty addAll asList dataset html toString equals hashCode clone normalize deduplicate"),
        Attribute::class.java to names("getKey setKey getValue hasDeclaredValue setValue html toString equals hashCode clone"),
        Document.OutputSettings::class.java to names("escapeMode charset syntax prettyPrint outline indentAmount maxPaddingWidth clone"),
        Tag::class.java to names("getName normalName namespace isBlock formatAsBlock isEmpty isSelfClosing isKnownTag preserveWhitespace isFormListed isFormSubmittable equals hashCode toString"),
    )
    private val methods = exports.mapValues { (type, names) -> type.methods.filter {
        it.name in names && !it.isBridge && !it.isSynthetic && !Modifier.isStatic(it.modifiers) &&
            it.parameterTypes.none { parameter -> parameter == Class::class.java || parameter == Appendable::class.java || parameter.name == "org.jsoup.select.Evaluator" }
    }.groupBy { it.name } }

    private val queries = names("select selectFirst expectFirst selectXpath is closest getElementsByTag getElementById getElementsByClass getElementsByAttribute getElementsByAttributeValue getElementsContainingText getElementsContainingOwnText hasAttr hasClass hasText")
    private val getters = names("text wholeText wholeOwnText ownText html outerHtml toString data val attr tagName id className children childrenSize first last getAllElements")
    private val collectionReads = names("get size isEmpty contains containsAll indexOf lastIndexOf subList toArray")
    private val collectionWrites = names("add addAll set clear removeAll retainAll")
    private fun readOnly(name: String, args: Array<out Any>) = name in queries ||
        args.isEmpty() && name in getters || name == "attr" && args.size == 1

    private fun checkNode(node: Node, force: Boolean) {
        val context = Context.getCurrentContext()
        val limit = context.getThreadLocal(bridgeLimitKey) as Int
        val root = node.root()
        val checked = ScriptRealm.current(context).checkedDomLimits
        // Every mutation still checks its owning tree. Only known pure reads can reuse it;
        // retained DOM values must be checked again if a later invocation has a smaller budget.
        if (force || checked[root]?.let { it > limit } != false) {
            BoundedJsonResult(limit).encode(root.outerHtml())
            checked[root] = limit
        }
    }

    fun parse(cx: Context, scope: Scriptable, html: String, baseUrl: String, xml: Boolean = false): ScriptableObject =
        node(cx, scope, Jsoup.parse(html, baseUrl, if (xml) Parser.xmlParser() else Parser.htmlParser()))

    fun fragment(cx: Context, scope: Scriptable, html: String, baseUrl: String, xml: Boolean): ScriptableObject {
        val doc = Jsoup.parse(html, baseUrl, if (xml) Parser.xmlParser() else Parser.htmlParser())
        return node(cx, scope, (if (xml) doc.children().firstOrNull() else doc.body().children().firstOrNull()) ?: doc)
    }

    fun node(cx: Context, scope: Scriptable, element: Element): ScriptableObject = wrap(cx, scope, element) as ScriptableObject
    fun elements(cx: Context, scope: Scriptable, nodes: List<Element>): NativeArray = wrap(cx, scope, Elements(nodes)) as NativeArray

    fun snapshot(value: Any): JsonElement = when (value) {
        is java.io.InputStream, is java.io.Reader, is okio.BufferedSource -> JsonObject(emptyMap())
        is org.jsoup.Connection.Response -> buildJsonObject { put("url", value.url().toString()); put("status", value.statusCode()) }
        is Node -> JsonPrimitive(value.outerHtml())
        is Attributes -> JsonObject(value.associate { it.key to JsonPrimitive(it.value) })
        is Attribute -> buildJsonObject { put("key", value.key); put("value", value.value) }
        is Document.OutputSettings -> buildJsonObject { put("charset", value.charset().name()); put("syntax", value.syntax().name); put("prettyPrint", value.prettyPrint()) }
        else -> JsonPrimitive(value.toString())
    }

    fun wrap(cx: Context, scope: Scriptable, value: Any?, ownerCheck: (() -> Unit)? = null): Any? {
        val realm = ScriptRealm.current(cx)
        val checkState: (() -> Unit)? = when (value) {
            is ResponseSnapshot -> { { value.checkSize(Context.getCurrentContext().getThreadLocal(bridgeLimitKey) as Int) } }
            is Node -> { { checkNode(value, true) } }
            else -> ownerCheck
        }
        if (value == null) return null
        if (value is String || value is Number || value is Boolean) return value
        if (value is Enum<*>) return ScriptData.enum(cx, scope, value)
        if (value is Charset) return ScriptData.charset(cx, scope, value)
        if (value is java.net.URL) return ScriptData.url(cx, scope, value)
        if (value is Map<*, *>) return ScriptData.map(cx, scope, value, ownerCheck = checkState)
        val type = exports.keys.firstOrNull { it.isInstance(value) }
        if (type == null) {
            if (value is List<*>) return ScriptData.list(cx, scope, value as MutableList<*>, checkState)
            if (value is Iterable<*>) return ScriptData.list(cx, scope, value.toMutableList(), checkState)
            if (value is Array<*>) return realm.arrayIn(scope, value.map { wrap(cx, scope, it, checkState) }.toTypedArray())
            if (value is ByteArray) return JsonScriptData(cx, scope, cx.getThreadLocal(bridgeLimitKey) as Int).convert(ScriptTools.bytes(value))
            if (value is java.io.InputStream) return ScriptStreams.wrap(cx, scope, value)
            if (value is java.io.Reader) return ScriptStreams.wrap(cx, scope, value)
            error("Unsupported DOM value")
        }
        val result: ScriptableObject = if (value is Elements) realm.arrayIn(scope, value.map { wrap(cx, scope, it) }.toTypedArray())
            else (if (value is Element) ScriptDomElement(value) else ScriptDomValue(value)).apply { parentScope = scope; prototype = realm.objectIn(scope).prototype }
        for ((name, candidates) in methods.getValue(type)) {
            result.defineProperty(name, ScriptCalls.method(scope, if (value is org.jsoup.Connection.Response) "host bridge denied" else "invalid DOM argument") { context, active, args ->
                val selected = candidates.sortedBy { it.isVarArgs }.firstNotNullOfOrNull { method ->
                    arguments(method.parameterTypes, method.isVarArgs, args)?.let { method to it }
                } ?: error("Unsupported DOM overload")
                val collectionRead = value is Elements && name in collectionReads
                val pure = collectionRead || readOnly(name, args)
                if (!pure) ScriptRealm.current(context).checkedDomLimits.clear()
                // Derived maps/lists retain this check, including void-returning mutations.
                val returned = try { selected.first.invoke(value, *selected.second) }
                catch (failure: java.lang.reflect.InvocationTargetException) {
                    when (val cause = failure.cause) { is Error -> throw cause; is java.util.concurrent.CancellationException -> throw cause; else -> throw IllegalArgumentException("Invalid DOM operation") }
                }
                finally {
                    if (pure && value is Node) checkNode(value, false)
                    else if (pure && value is Elements && !collectionRead) value.forEach { checkNode(it, false) }
                    else checkState?.invoke()
                }
                if (collectionRead && returned is Node) checkNode(returned, false)
                if (value is Elements && (name in collectionWrites || name == "remove" && args.isNotEmpty())) {
                    for (i in value.size until (result as NativeArray).length.toInt()) result.delete(i)
                    result.put("length", result, value.size)
                    value.forEachIndexed { index, element -> result.put(index, result, wrap(context, active, element)) }
                }
                if (returned === value) result else wrap(context, active, returned, checkState)
            }, ScriptableObject.DONTENUM)
        }
        if (value is Node || value is Elements) {
            for (name in listOf("traverse", "filter", "forEachNode", "forEach")) {
                val arrayFilter = if (result is NativeArray && name == "filter") ScriptableObject.getProperty(result, "filter") as? Callable else null
                result.defineProperty(name, ScriptCalls.method(scope, "invalid DOM callback") { context, active, args ->
                    require(args.size == 1)
                    ScriptRealm.current(context).checkedDomLimits.clear()
                    val callback = args[0]
                    if (arrayFilter != null && callback is Callable) return@method arrayFilter.call(context, active, result, args)
                    fun invoke(part: String, node: Node, depth: Int): Any? {
                        val function = if (callback is Callable && part == "head") callback
                            else if (callback is Scriptable) ScriptableObject.getProperty(callback, part) as? Callable else null
                        if (function == null) { require(part == "tail"); return "CONTINUE" }
                        return function.call(context, active, callback as? Scriptable ?: result, arrayOf(wrap(context, active, node), depth))
                    }
                    val roots = if (value is Elements) value.toList() else listOf(value as Node)
                    if (name == "filter") {
                        val filter = object : org.jsoup.select.NodeFilter {
                            override fun head(node: Node, depth: Int) = org.jsoup.select.NodeFilter.FilterResult.valueOf(Context.toString(invoke("head", node, depth)))
                            override fun tail(node: Node, depth: Int) = org.jsoup.select.NodeFilter.FilterResult.valueOf(Context.toString(invoke("tail", node, depth)))
                        }
                        roots.forEach { org.jsoup.select.NodeTraversor.filter(filter, it) }
                    } else {
                        val visitor = object : org.jsoup.select.NodeVisitor {
                            override fun head(node: Node, depth: Int) { if (name != "forEach" || node is Element) invoke("head", node, depth) }
                            override fun tail(node: Node, depth: Int) { if (name == "traverse") invoke("tail", node, depth) }
                        }
                        if (name == "forEach" && value is Elements) value.forEach { invoke("head", it, 0) }
                        else roots.forEach { org.jsoup.select.NodeTraversor.traverse(visitor, it) }
                    }
                    roots.forEach { BoundedJsonResult(context.getThreadLocal(bridgeLimitKey) as Int).encode(it.root().outerHtml()) }
                    result
                }, ScriptableObject.DONTENUM)
            }
        }
        return result
    }

    internal fun arguments(types: Array<Class<*>>, varargs: Boolean, args: Array<out Any>): Array<Any?>? {
        if (!varargs && types.size != args.size || varargs && args.size < types.size - 1) return null
        return try {
            Array(types.size) { index ->
                if (varargs && index == types.lastIndex) {
                    if (args.size == types.size && args[index] is NativeArray) convert(args[index], types[index])
                    else java.lang.reflect.Array.newInstance(types[index].componentType, args.size - index).also { array ->
                        for (i in index until args.size) java.lang.reflect.Array.set(array, i - index, convert(args[i], types[index].componentType))
                    }
                } else convert(args[index], types[index])
            }
        } catch (_: IllegalArgumentException) { null }
    }

    private fun convert(value: Any?, type: Class<*>): Any? {
        val raw = if (value is ScriptDomValue) value.value else value
        if (raw == null || Undefined.isUndefined(raw)) { require(!type.isPrimitive); return null }
        if (type.isInstance(raw) && raw !is Scriptable) return raw
        return when {
            type == String::class.java -> { require(raw is CharSequence); raw.toString() }
            type == Int::class.javaPrimitiveType -> { require(raw is Number); val n = raw.toDouble(); require(n.isFinite() && n == n.toInt().toDouble()); n.toInt() }
            type == Long::class.javaPrimitiveType -> { require(raw is Number); val n = raw.toDouble(); require(n.isFinite() && n == n.toLong().toDouble()); n.toLong() }
            type == Byte::class.javaPrimitiveType -> { require(raw is Number && raw.toInt() in -128..255); raw.toByte() }
            type == Boolean::class.javaPrimitiveType -> { require(raw is Boolean); raw }
            type == Charset::class.java -> Charset.forName(if (raw is CharSequence) raw.toString() else error("Charset required"))
            type == java.net.URL::class.java -> java.net.URL(raw.toString())
            type.isEnum -> type.enumConstants.firstOrNull { (it as Enum<*>).name.equals(raw.toString(), true) } ?: throw IllegalArgumentException()
            type.isArray && raw is NativeArray -> java.lang.reflect.Array.newInstance(type.componentType, raw.length.toInt()).also { array ->
                for (i in 0 until raw.length.toInt()) java.lang.reflect.Array.set(array, i, convert(raw.get(i, raw), type.componentType))
            }
            java.util.Collection::class.java.isAssignableFrom(type) && raw is NativeArray -> {
                val values = (0 until raw.length.toInt()).map { (raw.get(it, raw) as? ScriptDomValue)?.value ?: raw.get(it, raw) }
                require(values.none { it is Scriptable })
                if (java.util.Set::class.java.isAssignableFrom(type)) values.toMutableSet() else values.toMutableList()
            }
            else -> throw IllegalArgumentException("Unsupported DOM argument")
        }
    }
}
