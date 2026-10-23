package com.phonecompanion.app.notify

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM receiver stub.
 * TODO (notifications PR): channel routing, kinds, priorities, actions,
 * image attachments, targeted vs broadcast handling.
 */
class CompanionMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // TODO (notifications PR): render per docs/api-contract.md.
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // TODO (pairing PR): upload token to backend for this device.
    }
}
