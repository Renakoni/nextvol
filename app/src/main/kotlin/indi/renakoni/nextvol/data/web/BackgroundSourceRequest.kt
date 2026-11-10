package indi.renakoni.nextvol.data.web

import kotlinx.coroutines.Deferred
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Resume this task through its normal worker; script interaction belongs to the user action. */
class BackgroundSourceRequest(
    val verificationId: String? = null,
    val prepareResume: suspend () -> (suspend (String?, Deferred<Unit>?) -> Unit)?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BackgroundSourceRequest>
}
