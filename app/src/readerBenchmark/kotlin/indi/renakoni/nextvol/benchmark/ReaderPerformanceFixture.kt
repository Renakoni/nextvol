package indi.renakoni.nextvol.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.ChapterContentEntity
import indi.renakoni.nextvol.data.local.room.entity.ChapterInformationEntity
import indi.renakoni.nextvol.data.local.room.entity.UserReadingDataEntity
import indi.renakoni.nextvol.data.local.room.entity.VolumeEntity
import indi.renakoni.nextvol.data.web.SourceCapability
import indi.renakoni.nextvol.data.web.SourceMetadata
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import java.io.File
import java.time.LocalDateTime

/** Deterministic native-source fixture. No DNS, HTTP, accounts or external sites. */
internal object ReaderPerformanceFixture {
    const val REVISION = "reader-fixture-v1"
    val sourceId = Identifier("benchmark", "reader")
    val book = SourceBookId(sourceId, "fixture")
    fun chapter(id: String) = SourceChapterId(book, id).storageKey
    val metadata = SourceMetadata(WebDataSourceItem(sourceId, "Reader fixture", "Tests"),
        setOf(SourceCapability.Directory, SourceCapability.ChapterContent), revision = REVISION)
    fun preferences(context: Context) = context.getSharedPreferences("reader-performance", Context.MODE_PRIVATE)
    fun profile(context: Context) = preferences(context).getString("profile", "short")!!
    fun text(id: String, profile: String): String {
        val count = when (profile) {
            "long", "paragraph" -> 2_000
            "components" -> 1_097
            "components-short" -> 29
            else -> 30
        }
        val separator = if (profile == "paragraph") " " else "\n\n"
        return (0 until count).joinToString(separator) { index ->
            "R${id}_P${index.toString().padStart(4, '0')} 中文原文𠮷🙂 deterministic reader text preserves every source offset."
        }
    }
    fun content(context: Context, id: String): ChapterContent {
        val builder = ContentBuilder()
        textComponents(id, profile(context)).forEach { builder.simpleText(it) }
        if (profile(context) == "mixed") {
            builder.image(Uri.fromFile(File(context.filesDir, "reader-fixture.png")))
                .simpleText("R${id}_AFTER_IMAGE 原文 after the deterministic local image.")
        }
        return ChapterContent(id, "Reader chapter $id", builder.build(),
            if (id == "2") "1" else null, if (id == "1") "2" else null)
    }
    fun textComponents(id: String, profile: String): List<String> =
        if (profile.startsWith("components")) text(id, profile).split("\n\n") else listOf(text(id, profile))

    suspend fun seed(context: Context, profile: String, cache: String) {
        require(profile in setOf("short", "long", "paragraph", "mixed", "components", "components-short"))
        require(cache in setOf("trusted", "legacy", "missing"))
        preferences(context).edit().putString("profile", profile).putString("cache", cache)
            .putInt("utf16PerChapter", text("1", profile).length).commit()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
        File(context.filesDir, "reader-fixture.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val db = NextVolDatabase.getInstance(context)
        val settings = UserDataRepository(db.userDataDao())
        settings.floatUserData(UserDataPath.Reader.FontSize.path).set(15f)
        settings.booleanUserData(UserDataPath.Reader.IsUsingFlipPage.path).set(false)
        settings.booleanUserData(UserDataPath.Reader.IsUsingContinuousScrolling.path).set(true)
        settings.uriUserData(UserDataPath.Reader.FontFamilyUri.path).set(Uri.EMPTY)
        db.chapterContentDao().deleteByIds(listOf(chapter("1"), chapter("2"), chapter("background")))
        db.bookVolumesDao().insertVolume(VolumeEntity(book.storageKey, BookIdentity.volumeKey(book, "volume"),
            "Reader fixture", listOf(chapter("1"), chapter("2")), 0))
        db.bookVolumesDao().insertChapterInformationEntities(
            ChapterInformationEntity(chapter("1"), "Reader chapter 1"),
            ChapterInformationEntity(chapter("2"), "Reader chapter 2"))
        if (cache != "missing") for (id in listOf("1", "2")) {
            val data = content(context, id)
            db.chapterContentDao().update(ChapterContentEntity(chapter(id), data.title, data.content,
                data.prevChapter?.let(::chapter).orEmpty(), data.nextChapter?.let(::chapter).orEmpty(),
                if (cache == "trusted") REVISION else ""))
        }
        val now = LocalDateTime.of(2026, 1, 1, 0, 0)
        db.userReadingDataDao().insert(UserReadingDataEntity(book.storageKey, now, 0, 0f,
            chapter("1"), "Reader chapter 1", emptyMap(), emptyMap()))
    }
}
