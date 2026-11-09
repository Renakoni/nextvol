package indi.renakoni.nextvol.utils

import io.nightfish.lightnovelreader.api.identifier.Identifier

internal fun String.ofId() = Identifier("lightnovelreader", this)