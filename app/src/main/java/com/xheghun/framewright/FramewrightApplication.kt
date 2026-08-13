package com.xheghun.framewright

import android.app.Application
import android.util.Log
import com.xheghun.framewright.storage.FramewrightStorage

class FramewrightApplication : Application() {
    val diagnosticsStorage: FramewrightStorage by lazy {
        FramewrightStorage.create(this) { error, cause ->
            Log.e("FramewrightStorage", error.name, cause)
        }
    }
}
