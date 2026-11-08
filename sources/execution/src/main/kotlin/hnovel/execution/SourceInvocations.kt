package hnovel.execution

import hnovel.network.BrokerRequest
import hnovel.network.RequestRetryContext
import hnovel.network.SourceSession

/** Host-bound creation only; each invocation owns its budget and closes independently of the session. */
class SourceInvocations(private val identity: ExecutionIdentity, private val authority: ExecutionAuthority,
    private val session: SourceSession) {
    fun create(limits: ExecutionLimits, baseUrl: String = "", keyword: String = "", page: Int = 1,
        allowInteraction: Boolean = false, sourceName: String = "", sourceLastUpdateTime: Long = 0,
        requestUserAgent: String? = null, currentRequest: BrokerRequest? = null,
        memory: ScriptMemory = ScriptMemory(), onMessage: ((String) -> Unit)? = null,
        retryContext: RequestRetryContext? = null, directoryFields: Int = 0): SourceExecutionBroker =
        SourceExecutionBroker(identity, authority, session, limits, baseUrl, keyword, page,
            allowInteraction = allowInteraction, sourceName = sourceName, sourceLastUpdateTime = sourceLastUpdateTime,
            requestUserAgent = requestUserAgent, currentRequest = currentRequest, memory = memory,
            onMessage = onMessage, retryContext = retryContext, directoryFields = directoryFields)
}
