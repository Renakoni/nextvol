package indi.renakoni.nextvol.ui.components

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.github.michaelbull.result.get
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Some sources list books without covers. Their cards borrow the cover from the book's details, a few reads at a time. */
@HiltViewModel
class ListCoverViewModel internal constructor(private val detailCover: suspend (String) -> Uri?) : ViewModel() {
    @Inject constructor(books: BookRepository) : this({ books.detailCover(it).get() })

    private val gate = Semaphore(4)
    // Uri.EMPTY records details without a cover. A failed read is not kept, so a later visit tries again.
    private val found = ConcurrentHashMap<String, Uri>()
    // The same book in two sections waits for one read instead of starting a second.
    private val reading = ConcurrentHashMap<String, Mutex>()

    suspend fun cover(bookId: String): Uri? = (found[bookId] ?: reading.getOrPut(bookId) { Mutex() }.withLock {
        found[bookId] ?: gate.withPermit { read(bookId) }?.also { found[bookId] = it }
    })?.takeIf { it.toString().isNotBlank() }

    // As in search results, a slow or failing source keeps the generated cover and is tried again later.
    private suspend fun read(bookId: String): Uri? = try {
        withTimeoutOrNull(30_000) { detailCover(bookId) }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
}

/** A card whose list gave no cover shows the generated cover until the book's own cover is known. */
@Composable
fun rememberListCover(bookId: String, coverUrl: String, coverFor: suspend (String) -> Uri?): Uri {
    var found by remember(bookId, coverUrl) { mutableStateOf<Uri?>(null) }
    // Only cards on screen look up; leaving the screen cancels the wait or the read.
    if (coverUrl.isBlank()) LaunchedEffect(bookId) { found = coverFor(bookId) }
    return found ?: Uri.parse(coverUrl)
}
