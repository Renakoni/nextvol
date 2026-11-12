package indi.renakoni.nextvol.data.web

import hnovel.content.ContentError
import hnovel.content.SourceContentException
import hnovel.network.BrowserChallengeKind
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError

internal fun SourceContentException.discoveryError() = when (code) {
    ContentError.MissingCapability -> DiscoveryError.Unsupported
    ContentError.LoginRequired -> DiscoveryError.AuthenticationRequired
    ContentError.BrowserRequired -> if (verification?.kind == BrowserChallengeKind.Login)
        DiscoveryError.AuthenticationRequired else DiscoveryError.VerificationRequired
    ContentError.PermissionDenied -> DiscoveryError.PermissionDenied
    ContentError.AddressDenied -> DiscoveryError.AddressDenied
    ContentError.Dns -> DiscoveryError.Dns
    ContentError.RouteUnavailable -> DiscoveryError.RouteUnavailable
    ContentError.RouteUnsupported -> DiscoveryError.RouteUnsupported
    ContentError.Certificate -> DiscoveryError.Certificate
    ContentError.Network -> DiscoveryError.Network
    ContentError.Unavailable -> DiscoveryError.Unavailable
    ContentError.Limit -> DiscoveryError.Limit
    else -> DiscoveryError.InvalidRules
}

internal fun SourceContentException.requestErrorKind() = when (code) {
    ContentError.LoginRequired -> WebRequestErrorKind.AuthenticationRequired
    ContentError.BrowserRequired -> if (verification?.kind == BrowserChallengeKind.Login)
        WebRequestErrorKind.AuthenticationRequired else WebRequestErrorKind.VerificationRequired
    ContentError.Unavailable -> WebRequestErrorKind.SourceUnavailable
    else -> WebRequestErrorKind.Other
}
