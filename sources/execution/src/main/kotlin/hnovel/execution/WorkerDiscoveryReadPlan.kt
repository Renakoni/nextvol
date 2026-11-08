package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.Json
import org.mozilla.javascript.CompilerEnvirons
import org.mozilla.javascript.Context
import org.mozilla.javascript.Parser
import org.mozilla.javascript.Token
import org.mozilla.javascript.ast.*

/** A conservative syntax check in the isolated worker, never execution of a source on the host.
 * Request cache reads are snapshotted before dispatch. Read-only response rules can run without shared library state. */
internal object WorkerDiscoveryReadPlan {
    fun evaluate(task: ExecutionTask.DiscoveryReadPlan, limits: ExecutionLimits): ExecutionResult {
        val accepted = task.urls.size in 1..64 && task.rules.size <= 64 &&
            (task.urls + task.rules + task.header).sumOf { it.length } <= 65536 && runCatching {
                task.urls.all(::url) && header(task.header) && task.rules.all(::rule)
            }.getOrDefault(false)
        val output = Json.encodeToString(ExecutedRule.serializer(), ExecutedRule(RuleValue.Text(accepted.toString()), emptyMap()))
        return if (output.toByteArray().size > limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit)
            else ExecutionResult.Success(output)
    }

    private fun header(value: String): Boolean = when {
        value.trimStart().startsWith("@js:", true) -> script(value.trimStart().substring(4), selectors = false)
        value.trimStart().startsWith("<js>", true) -> value.trimEnd().endsWith("</js>", true) &&
            script(value.trim().let { it.substring(4, it.length - 5) }, selectors = false)
        else -> ExecutionTask.BookOverviews.supports(value)
    }

    private fun url(value: String): Boolean {
        if (value.trimStart().let { it.startsWith("@js:", true) || it.startsWith("<js>", true) }) return header(value)
        val parser = RuleParser()
        val budget = RuleBudget()
        var index = 0
        while (index < value.length) {
            if (!value.startsWith("{{", index)) { index++; continue }
            val end = parser.balancedEnd(value, index, RuleLocation("exploreUrl"), budget)
            if (!script(value.substring(index + 2, end - 2), selectors = false)) return false
            index = end
        }
        return listOf("@js:", "<js>", "@get:", "@put:").none { value.contains(it, true) }
    }

    private fun rule(value: String): Boolean = RuleParser().parse(value, RuleLocation("ruleExplore"), RuleBudget()).steps.all {
        // RuleEvaluator interpolates scripts before execution, including inside string literals.
        // Their raw AST cannot prove the behavior of template or capture substitutions.
        if (it.script) listOf("{{", "@get:", "@put:").none { marker -> it.text.contains(marker, true) } &&
            !Regex("""\$\d""").containsMatchIn(it.text) && script(it.text, selectors = true)
        else ExecutionTask.BookOverviews.supports(it.text)
    }

    internal fun isReadOnlyRule(value: String): Boolean = value.length <= 65536 && runCatching { rule(value) }.getOrDefault(false)

    private val reserved = setOf("baseUrl", "result", "key", "page", "infoMap", "java", "cache", "JSON", "String", "Number",
        "parseInt", "parseFloat", "encodeURIComponent", "decodeURIComponent", "undefined", "org")
    private val properties = setOf("constructor", "prototype", "__proto__", "get", "set", "save")
    private val operators = setOf(Token.ADD, Token.SUB, Token.MUL, Token.DIV, Token.MOD, Token.EQ, Token.NE,
        Token.SHEQ, Token.SHNE, Token.LT, Token.LE, Token.GT, Token.GE, Token.AND, Token.OR,
        Token.BITAND, Token.BITOR, Token.BITXOR, Token.LSH, Token.RSH, Token.URSH, Token.COMMA)

    private fun script(code: String, selectors: Boolean): Boolean {
        val tree = Parser(CompilerEnvirons().apply { languageVersion = Context.VERSION_ES6 }).parse(code, "discovery-plan", 1)
        val locals = mutableSetOf<String>()
        val statements = tree.map { it as AstNode }
        return statements.isNotEmpty() && statements.all { node -> when (node) {
            is VariableDeclaration -> node.variables.all { binding ->
                val name = (binding.target as? Name)?.identifier
                name != null && name !in reserved && name !in locals && binding.initializer != null &&
                    expression(binding.initializer, locals, selectors) && locals.add(name)
            }
            is ExpressionStatement -> expression(node.expression, locals, selectors)
            is EmptyStatement -> true
            else -> false
        } }
    }

    private fun expression(node: AstNode, locals: Set<String>, selectors: Boolean): Boolean {
        fun safe(value: AstNode) = expression(value, locals, selectors)
        return when (node) {
            is StringLiteral, is NumberLiteral, is RegExpLiteral -> true
            is KeywordLiteral -> node.type in setOf(Token.NULL, Token.TRUE, Token.FALSE)
            is Name -> node.identifier in locals || node.identifier in setOf("baseUrl", "result", "key", "page", "undefined")
            is ParenthesizedExpression -> safe(node.expression)
            is ConditionalExpression -> safe(node.testExpression) && safe(node.trueExpression) && safe(node.falseExpression)
            is ArrayLiteral -> node.elements.all(::safe)
            is ObjectLiteral -> node.elements.all { property ->
                val name = when (val left = property.left) {
                    is Name -> left.identifier
                    is StringLiteral -> left.value
                    is NumberLiteral -> left.value
                    else -> null
                }
                property.type == Token.COLON && name != null && name !in properties && safe(property.right)
            }
            is ElementGet -> if ((node.target as? Name)?.identifier == "infoMap")
                (node.element as? StringLiteral)?.value?.let { it !in properties } == true
                else safe(node.target) && safe(node.element)
            is PropertyGet -> if ((node.target as? Name)?.identifier == "infoMap") node.property.identifier !in properties
                else node.property.identifier == "length" && safe(node.target)
            is NewExpression -> false
            is FunctionCall -> {
                val target = node.target
                when {
                    target is Name -> target.identifier in setOf("String", "Number", "parseInt", "parseFloat",
                        "encodeURIComponent", "decodeURIComponent") && node.arguments.all(::safe)
                    target is PropertyGet -> {
                        val owner = (target.target as? Name)?.identifier
                        val method = target.property.identifier
                        when {
                            selectors && target.target.toSource() == "org.jsoup.Jsoup" ->
                                method == "parse" && node.arguments.size == 1 && node.arguments.all(::safe)
                            owner == "cache" -> !selectors && method == "get" && node.arguments.size == 1 &&
                                node.arguments.single() is StringLiteral
                            owner == "java" -> if (method == "getWebViewUA") node.arguments.isEmpty()
                                else selectors && method in setOf("getElements", "getString") && node.arguments.size == 1 &&
                                    (node.arguments.single() as? StringLiteral)?.value?.let(ExecutionTask.BookOverviews::supports) == true
                            owner == "JSON" -> method == "stringify" && node.arguments.size == 1 && node.arguments.all(::safe)
                            owner == "String" -> method == "fromCharCode" && node.arguments.all(::safe)
                            selectors && method in setOf("select", "selectFirst", "attr") ->
                                node.arguments.size == 1 && safe(target.target) && node.arguments.all(::safe)
                            selectors && method in setOf("first", "text", "html") ->
                                node.arguments.isEmpty() && safe(target.target)
                            else -> method in setOf("match", "test", "split", "slice", "join", "replace", "trim", "toLowerCase", "toUpperCase") &&
                                safe(target.target) && node.arguments.all(::safe)
                        }
                    }
                    else -> false
                }
            }
            is InfixExpression -> node.operator in operators && safe(node.left) && safe(node.right)
            is UnaryExpression -> node.operator in setOf(Token.NOT, Token.POS, Token.NEG, Token.BITNOT, Token.TYPEOF, Token.VOID) && safe(node.operand)
            else -> false
        }
    }
}
