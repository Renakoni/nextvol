package indi.renakoni.nextvol.tts

/** Device acceptance observes the same controller state as the production reader. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SpeechDebugEntryPoint {
    fun controller(): ReadAloudController
    fun voices(): SpeechVoiceCatalog
    fun plans(): SpeechPlanPreparer
}
