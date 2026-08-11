package com.coveninja.cove

import android.app.Application
import com.coveninja.cove.backend.AndroidBackendRuntime

class CoveMobileApplication : Application() {
    @Volatile
    private var backend: AndroidBackendRuntime? = null

    fun backendRuntime(): AndroidBackendRuntime = backend ?: synchronized(this) {
        backend ?: AndroidBackendRuntime.open(
            context = this,
            tmdbApiKey = BuildConfig.TMDB_API_KEY,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ).also { backend = it }
    }

    override fun onTerminate() {
        backend?.close()
        backend = null
        super.onTerminate()
    }
}
