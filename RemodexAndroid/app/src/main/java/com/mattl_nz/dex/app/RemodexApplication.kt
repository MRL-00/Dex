package com.mattl_nz.dex.app

import android.app.Application
import com.mattl_nz.dex.core.data.AndroidMessageHistoryStore
import com.mattl_nz.dex.core.data.RemodexRepository
import com.mattl_nz.dex.core.notification.RemodexNotificationService
import com.mattl_nz.dex.core.security.AndroidSecureStorage
import kotlinx.serialization.json.Json

class RemodexApplication : Application() {
    private val appJson by lazy {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    val repository: RemodexRepository by lazy {
        RemodexRepository(
            storage = AndroidSecureStorage(
                context = this,
                json = appJson,
            ),
            messageHistoryStore = AndroidMessageHistoryStore(
                context = this,
                json = appJson,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        repository.notificationService = RemodexNotificationService(this)
    }
}
