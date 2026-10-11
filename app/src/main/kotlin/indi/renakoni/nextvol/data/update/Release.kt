package indi.renakoni.nextvol.data.update

interface Release {
    val version: Int
    val versionName: String
    val releaseNotes: String
    val downloadUrl: String
}
