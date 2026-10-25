package dev.cluvex.strata.core.bookmarks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bookmarkStore: DataStore<Preferences> by preferencesDataStore(name = "strata_bookmarks")

class BookmarkRepository(private val context: Context) {

    private val key = stringPreferencesKey("bookmarks")

    val bookmarks: Flow<List<Bookmark>> = context.bookmarkStore.data.map { Bookmarks.decode(it[key]) }

    suspend fun edit(transform: (List<Bookmark>) -> List<Bookmark>): List<Bookmark> {
        var result = emptyList<Bookmark>()
        context.bookmarkStore.edit { prefs ->
            result = transform(Bookmarks.decode(prefs[key]))
            prefs[key] = Bookmarks.encode(result)
        }
        return result
    }
}
