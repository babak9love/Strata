package dev.cluvex.strata.ui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import java.util.concurrent.atomic.AtomicLong

class RouteEntry(val route: Route, private val app: Application) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    val key: Long = ids.incrementAndGet()

    override val viewModelStore = ViewModelStore()

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = ViewModelProvider.AndroidViewModelFactory.getInstance(app)

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras().apply { set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app) }

    private companion object {
        val ids = AtomicLong()
    }
}

class RouteStack(private val app: Application) {
    val entries: SnapshotStateList<RouteEntry> = mutableStateListOf()

    val top: RouteEntry? get() = entries.lastOrNull()
    val route: Route get() = top?.route ?: Route.Files

    fun push(route: Route) {
        if (route == Route.Files) clear() else entries.add(RouteEntry(route, app))
    }

    fun replace(route: Route) {
        entries.removeLastOrNull()
        push(route)
    }

    fun pop() {
        entries.removeLastOrNull()
    }

    fun clear() = entries.clear()

    fun contains(entry: RouteEntry) = entry in entries
}
