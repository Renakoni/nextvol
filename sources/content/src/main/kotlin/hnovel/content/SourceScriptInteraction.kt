package hnovel.content

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Host-owned continuation; source code cannot grant foreground interaction. */
class SourceScriptInteraction(val request: suspend (SourceVerification) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SourceScriptInteraction>
}

/** Background work must restart under user interaction, never retain a closed worker's callback. */
class SourceScriptInteractionRequired : SourceContentException(ContentError.BrowserRequired, "loginCheckJs")
