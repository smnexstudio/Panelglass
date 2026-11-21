package com.smnexstudio.panelglass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Holds view models built by hand in a store, so a test can clear them (cancelling their scopes) before it closes the
 * database they read: a scope left running would query a closed database and fail a later test.
 */
internal class TestViewModels {
    private val store = ViewModelStore()
    private val jobs = mutableListOf<Job>()
    private var n = 0

    fun <T : ViewModel> keep(vm: T): T {
        vm.viewModelScope.coroutineContext[Job]?.let { jobs += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
        }
        return ViewModelProvider(store, factory)["vm" + n++, vm.javaClass]
    }

    fun clear() {
        store.clear()
        // Cancellation is asynchronous: finish IO continuations before resetting Main or closing Room.
        runBlocking {
            withTimeout(10_000) {
                while (jobs.any { !it.isCompleted }) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(1)
                }
            }
        }
        jobs.clear()
    }
}
