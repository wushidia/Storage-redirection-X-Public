package com.storage.redirect.x

import android.app.Application
import com.storage.redirect.x.data.service.RootService

class StorageApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RootService.initialize(applicationInfo.sourceDir)
    }
}
