package indi.renakoni.nextvol.data.storage

/** Full chapter ID to book ID pairs; either owner keeps a chapter, and downloads win conflicts. */
internal fun chapterOwners(
    catalogChapters: Sequence<Pair<String, String>>,
    downloadedChapters: Sequence<Pair<String, String>>,
): Map<String, String> = buildMap {
    putAll(catalogChapters)
    putAll(downloadedChapters)
}
