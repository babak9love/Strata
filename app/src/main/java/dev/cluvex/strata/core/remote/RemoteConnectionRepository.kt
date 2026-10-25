package dev.cluvex.strata.core.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.remoteStore: DataStore<Preferences> by preferencesDataStore(name = "strata_remotes")

class RemoteConnectionRepository(private val context: Context) {

    private val key = stringPreferencesKey("connections")

    val connections: Flow<List<RemoteConnection>> = context.remoteStore.data.map { RemoteConnections.decode(it[key]) }

    suspend fun edit(transform: (List<RemoteConnection>) -> List<RemoteConnection>): List<RemoteConnection> {
        var result = emptyList<RemoteConnection>()
        context.remoteStore.edit { prefs ->
            result = transform(RemoteConnections.decode(prefs[key]))
            prefs[key] = RemoteConnections.encode(result)
        }
        return result
    }

    suspend fun save(connection: RemoteConnection) = edit { RemoteConnections.upsert(it, connection) }

    suspend fun remove(id: String) = edit { RemoteConnections.remove(it, id) }
}
