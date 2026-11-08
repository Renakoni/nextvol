package indi.renakoni.nextvol.data.update

data class Release(
    val version: Int,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val sha256: String,
)
