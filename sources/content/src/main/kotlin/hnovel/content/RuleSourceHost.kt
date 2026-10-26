package hnovel.content

import hnovel.execution.*
import hnovel.imports.SourceDefinition
import hnovel.network.*

/** Trusted activation entry. A failed construction revokes its ticket, but never closes the host's session. */
fun createRuleSource(definition: SourceDefinition, identity: ExecutionIdentity, authority: ExecutionAuthority,
    session: SourceSession, runner: RuleTaskRunner, trace: ContentTrace = ContentTrace.None,
    discoveryEnabled: Boolean = definition.enabledExplore): RuleSource =
    RuleSourceBinding.create(definition, identity, authority, session, runner, trace, discoveryEnabled)

/** Only the host factory can assemble these capabilities, all from the same checked session. */
internal class RuleSourceBinding private constructor(val definition: SourceDefinition, val spec: RuleSourceDefinition,
    val identity: ExecutionIdentity, val authority: ExecutionAuthority, val runner: RuleTaskRunner,
    val trace: ContentTrace, val discoveryEnabled: Boolean, val storage: SourceStateStorage,
    val invocations: SourceInvocations, val network: ContentNetwork, val loginSource: (ExecutionIdentity) -> RuleSource) {
    companion object {
        fun create(definition: SourceDefinition, identity: ExecutionIdentity, authority: ExecutionAuthority,
            session: SourceSession, runner: RuleTaskRunner, trace: ContentTrace, discoveryEnabled: Boolean): RuleSource = try {
            val spec = RuleSourceDefinition(definition)
            require(identity.sourceId == definition.sourceId && identity.profile == definition.profile && identity.revision == definition.contentDigest)
            require(session.scope.sourceId == identity.sourceId && session.scope.namespace == identity.namespace &&
                session.scope.profile == identity.profile && session.scope.accountGeneration == identity.accountGeneration)
            session.configureSource(spec.baseUrl, spec.cookiesEnabled, spec.browserRead, spec.concurrentRate,
                spec.localStorageRetention, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
            session.traceRequests(if (trace === ContentTrace.None) RequestTrace.None else RequestTrace { event ->
                trace.record(ContentTraceEvent("requestDiagnostic", "request", 0, result = event.evidence.name, requestDiagnostic = event))
            })
            RuleSource(RuleSourceBinding(definition, spec, identity, authority, runner, trace, discoveryEnabled,
                session, SourceInvocations(identity, authority, session), SessionContentNetwork(session)) { ticket ->
                createRuleSource(definition, ticket, authority, session, runner, trace, discoveryEnabled)
            })
        } catch (failure: Throwable) {
            authority.revoke(identity)
            throw failure
        }
    }
}

private class SessionContentNetwork(private val session: SourceSession) : ContentNetwork {
    override suspend fun execute(request: BrokerRequest, guard: RequestCommitGuard) = session.execute(request, guard)
    override suspend fun executeOnRoute(request: BrokerRequest, guard: RequestCommitGuard, route: SourceRouteReference) =
        session.executeOnRoute(request, guard, route)
    override suspend fun loadImage(request: BrokerRequest, guard: RequestCommitGuard) = session.loadImage(request, guard)
    override fun permissionFailureDetail(url: String) = session.permissionFailureDetail(url)
    override fun responseRoute() = session.responseRoute()
    override fun responseCookies(url: String) = session.responseCookies(url)
    override fun responseHeaders(request: BrokerRequest) = session.responseHeaders(request)
    override suspend fun requestUserAgent(url: String, explicit: Map<String, String>) = session.requestUserAgent(url, explicit)
    override fun approveCertificate(problem: CertificateProblem) = session.approveCertificate(problem)
}
