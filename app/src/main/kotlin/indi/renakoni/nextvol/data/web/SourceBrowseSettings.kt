package indi.renakoni.nextvol.data.web

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Discovery and categories share the user's selection; each page retains its own fallback. */
@Singleton
class SourceBrowseSettings @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("source-browsing", Context.MODE_PRIVATE)
    private val mutableScope = MutableStateFlow(SourceCategory.entries.find {
        it.name == preferences.getString("scope", null)
    })
    val scope = mutableScope.asStateFlow()
    private val mutableSelections = MutableStateFlow(
        (listOf<SourceCategory?>(null) + SourceCategory.entries).associateWith { selected("shared", it) })
    val selections = mutableSelections.asStateFlow()

    fun selectSource(category: SourceCategory?, id: Identifier) {
        if (selections.value[category] == id) return
        remember("shared", category, id)
        mutableSelections.value = mutableSelections.value + (category to id)
    }

    fun selectScope(category: SourceCategory?) {
        if (category == scope.value) return
        preferences.edit().putString("scope", category?.name).apply()
        mutableScope.value = category
    }

    fun selected(page: String, category: SourceCategory?): Identifier? {
        val key = "$page.${category?.name.orEmpty()}"
        val namespace = preferences.getString("$key.namespace", null) ?: return null
        return preferences.getString("$key.source", null)?.let { Identifier(namespace, it) }
    }

    fun remember(page: String, category: SourceCategory?, id: Identifier) {
        if (selected(page, category) == id) return
        val key = "$page.${category?.name.orEmpty()}"
        preferences.edit().putString("$key.namespace", id.namespace).putString("$key.source", id.id).apply()
    }
}
