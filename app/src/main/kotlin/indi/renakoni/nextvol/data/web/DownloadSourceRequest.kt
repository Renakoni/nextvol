package indi.renakoni.nextvol.data.web

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Downloads fetch their directory explicitly and consume the recent UI directory once. */
class DownloadSourceRequest : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DownloadSourceRequest>
}
