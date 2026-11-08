package hnovel.content

import hnovel.network.*

/** Content requests and response identity; session/account management stays with the host. */
internal interface ContentNetwork {
    suspend fun execute(request: BrokerRequest, guard: RequestCommitGuard): BrokerResult
    suspend fun executeOnRoute(request: BrokerRequest, guard: RequestCommitGuard, route: SourceRouteReference): BrokerResult
    suspend fun loadImage(request: BrokerRequest, guard: RequestCommitGuard): BrokerResult
    fun permissionFailureDetail(url: String): BrokerResult.Failure?
    fun responseRoute(): SourceRouteReference?
    fun responseCookies(url: String): List<String>
    fun responseHeaders(request: BrokerRequest): Map<String, String>
    suspend fun requestUserAgent(url: String, explicit: Map<String, String>): String
    fun approveCertificate(problem: CertificateProblem)
}
