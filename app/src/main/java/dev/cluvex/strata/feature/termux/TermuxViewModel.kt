package dev.cluvex.strata.feature.termux

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.core.termux.AndroidTermux
import dev.cluvex.strata.core.termux.TermuxSession

class TermuxViewModel(app: Application) : AndroidViewModel(app) {
    val session = TermuxSession(AndroidTermux(app), viewModelScope)
}
