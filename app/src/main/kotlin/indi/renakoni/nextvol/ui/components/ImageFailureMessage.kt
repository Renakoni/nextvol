package indi.renakoni.nextvol.ui.components

import android.content.Context
import hnovel.content.ContentError
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.image.SourceImageRequestException
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind

internal fun imageFailureMessage(context: Context, error: Throwable): String {
    val request = error as? SourceImageRequestException
    request?.httpStatus?.let { return context.getString(R.string.image_error_http, it) }
    return context.getString(when {
        request?.kind == WebRequestErrorKind.AuthenticationRequired -> R.string.sources_account_required
        request?.kind == WebRequestErrorKind.VerificationRequired -> R.string.sources_verification_required
        request?.kind == WebRequestErrorKind.SourceUnavailable -> R.string.sources_unavailable
        request?.contentError == ContentError.RouteUnavailable -> R.string.sources_route_unavailable
        request?.contentError in setOf(ContentError.PermissionDenied, ContentError.AddressDenied) -> R.string.image_error_address
        request?.contentError == ContentError.Limit -> R.string.image_error_limit
        request?.contentError == ContentError.InvalidRule -> R.string.image_error_rule
        request?.contentError in setOf(ContentError.Network, ContentError.Dns, ContentError.Certificate) ||
            request?.networkFailure == true || error is java.io.IOException && request == null -> R.string.image_error_network
        else -> R.string.image_error_decode
    })
}
