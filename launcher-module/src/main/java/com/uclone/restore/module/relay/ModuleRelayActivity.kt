package com.uclone.restore.module.relay

import android.app.Activity
import android.os.Bundle

class ModuleRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleRelayDispatcher.dispatch(this, intent, "activity")
        finish()
    }
}
