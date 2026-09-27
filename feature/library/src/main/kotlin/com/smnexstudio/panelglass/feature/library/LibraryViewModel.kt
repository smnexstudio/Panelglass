package com.smnexstudio.panelglass.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.HistoryRepository
import com.smnexstudio.panelglass.core.data.repo.SiteRepository
import com.smnexstudio.panelglass.core.model.HistoryEntry
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Settings
import com.smnexstudio.panelglass.core.model.Site
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val sites: SiteRepository,
    settingsRepo: SettingsRepository,
    private val history: HistoryRepository,
) : ViewModel() {
    val siteList: StateFlow<List<Site>> = sites.sites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val recent: StateFlow<List<HistoryEntry>> = history.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, url: String, sourceLang: Lang? = null, targetLang: Lang? = null) = viewModelScope.launch {
        val normalised = if (url.startsWith("http")) url.trim() else "https://" + url.trim()
        val display = name.trim().ifEmpty { Site.hostOf(normalised).substringBefore('.').replaceFirstChar { it.uppercase() } }
        sites.add(Site(name = display, url = normalised, sourceLang = sourceLang, targetLang = targetLang, adBlockEnabled = settings.value.adBlockDefault))
    }

    /**
     * Saves the pair picked before opening [site], then [then] (navigation) once the reader can read it back. A choice
     * equal to the Settings default is stored as "no override", so the site keeps following later Settings changes.
     */
    fun openWith(site: Site, src: Lang, tgt: Lang, then: () -> Unit) = viewModelScope.launch {
        val s = settings.value
        val updated = site.copy(
            sourceLang = src.takeIf { it != s.defaultSourceLang },
            targetLang = tgt.takeIf { it != s.defaultTargetLang },
        )
        if (updated != site) sites.update(updated)
        then()
    }

    fun update(site: Site) = viewModelScope.launch { sites.update(site) }
    fun delete(site: Site) = viewModelScope.launch { sites.delete(site) }
    fun togglePin(site: Site) = viewModelScope.launch { sites.setPinned(site, !site.pinned) }
    fun deleteHistory(entry: HistoryEntry) = viewModelScope.launch { history.delete(entry) }
    fun clearHistory() = viewModelScope.launch { history.clear() }
}
