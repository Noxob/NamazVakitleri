package com.noxob.namazvakti

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class SettingsRequestService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/request_settings") {
            SettingsSender(this).send()
        } else {
            super.onMessageReceived(messageEvent)
        }
    }
}
