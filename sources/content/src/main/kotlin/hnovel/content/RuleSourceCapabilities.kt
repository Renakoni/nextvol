package hnovel.content

import hnovel.imports.SourceDefinition

data class RuleSourceCapabilities(val search: Boolean, val login: Boolean, val feed: Boolean, val categories: Boolean,
    val directory: Boolean, val content: Boolean)

/** Static listing data; reading a definition creates no session and executes no expressions. */
fun ruleSourceCapabilities(definition: SourceDefinition, discoveryEnabled: Boolean = true): RuleSourceCapabilities {
    val spec = RuleSourceDefinition(definition)
    val discovery = RuleDiscoveryClassifier.capabilities(spec)
    return RuleSourceCapabilities(spec.searchUrl.isNotBlank(), spec.loginUrl.isNotBlank() || spec.loginUi.isNotBlank(),
        discoveryEnabled && discovery.hasFeed, discoveryEnabled && discovery.hasCategories,
        spec.directoryRule.isNotBlank(), spec.contentRule.isNotBlank())
}
