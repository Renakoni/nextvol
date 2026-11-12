package indi.renakoni.nextvol.ui.home.discovery

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryFilter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Each destination exposes only sources supporting its capability. */
internal fun discoverySources(sources: List<SourceListing>, capability: SourceCapability) = sources
    // Discovery owns lazy initialization. Keep registered sources visible while they
    // initialize, but never retain a source whose initialization has failed.
    .filter { it.status != SourceStatus.Failed && capability in it.metadata.capabilities }
    .filter { capability != SourceCapability.Explore || it.resolvedFeed != false }
    .sortedWith(compareBy({ !it.metadata.builtIn }, { it.metadata.id.namespace }, { it.metadata.id.id }))

internal fun selectedSource(sources: List<SourceListing>, requested: Identifier?): Identifier? =
    sources.firstOrNull { it.metadata.id == requested }?.metadata?.id ?: sources.firstOrNull()?.metadata?.id

internal data class DiscoveryVersion(val metadata: SourceMetadata, val registration: Long, val account: Long,
    val settings: Long = 0)
internal fun SourceListing.version(accounts: Map<Identifier, Long>) =
    DiscoveryVersion(metadata, generation, accounts[metadata.id] ?: metadata.accountGeneration, discoveryGeneration)

data class DiscoveryScroll(val index: Int = 0, val offset: Int = 0)

internal suspend fun <T> discoveryRequest(block: suspend () -> Result<T, DiscoveryError>): Result<T, DiscoveryError> =
    try { block() } catch (_: SourceUnavailableException) { Err(DiscoveryError.Unavailable) }
    catch (_: java.io.IOException) { Err(DiscoveryError.Network) }
    catch (_: Exception) {
        // A retired runtime may cancel its operation without cancelling the screen's coroutine.
        currentCoroutineContext().ensureActive()
        Err(DiscoveryError.Unavailable)
    }

internal suspend fun WebSourceRegistry.discovery(id: Identifier): Result<SourceDiscovery, DiscoveryError> =
    when (val resolution = resolve(id)) {
        is SourceResolution.Ready -> resolution.runtime.discovery?.let(::Ok) ?: Err(DiscoveryError.Unsupported)
        is SourceResolution.Missing -> Err(DiscoveryError.Unavailable)
        is SourceResolution.Unavailable -> Err(DiscoveryError.InvalidRules)
    }

/** Restored filters follow the current source definition; removed/invalid values use its defaults. */
internal fun filterValues(definitions: List<DiscoveryFilter>, saved: Map<String, String>): Map<String, String> =
    definitions.associate { filter -> filter.id to when (filter) {
        is DiscoveryFilter.Choice -> saved[filter.id]?.takeIf { it in filter.options } ?: filter.defaultValue
        is DiscoveryFilter.Toggle -> saved[filter.id]?.takeIf { it == "true" || it == "false" } ?: filter.defaultValue.toString()
        is DiscoveryFilter.Number -> saved[filter.id]?.toIntOrNull()?.takeIf { it in filter.min..filter.max }?.toString()
            ?: filter.defaultValue.toString()
        is DiscoveryFilter.Text -> saved[filter.id]?.takeIf { it.length <= 4096 } ?: filter.defaultValue
    } }
