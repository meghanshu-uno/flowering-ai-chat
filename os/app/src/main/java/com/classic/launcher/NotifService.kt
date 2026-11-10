package com.classic.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lets the notification center (swipe down from top-left) read the phone's notifications.
 * It only works after the user turns on "Notification access" for the launcher (type `setup`).
 */
class NotifService : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifService? = null
        var listener: (() -> Unit)? = null
    }

    override fun onListenerConnected() {
        instance = this
        listener?.invoke()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        listener?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        listener?.invoke()
    }
}
