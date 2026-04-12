package com.remodex.android.app

import android.app.Application
import com.remodex.android.core.data.AndroidMessageHistoryStore
import com.remodex.android.core.data.RemodexRepository
import com.remodex.android.core.notification.RemodexNotificationService
import com.remodex.android.core.security.AndroidSecureStorage
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
