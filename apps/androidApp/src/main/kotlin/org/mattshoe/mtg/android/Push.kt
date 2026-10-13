package org.mattshoe.mtg.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mattshoe.mtg.core.AdminToken
import org.mattshoe.mtg.core.MtgApi

/**
 * Where Firebase hands this phone its token, and its messages.
 *
 * The token is not something the app chooses or keeps: Firebase mints it
 * and rotates it on a reinstall, a restore, and sometimes nothing visible
 * at all. `onNewToken` is the only moment the new one exists, so it is
 * sent to the Worker here as well as at every app start — a phone that
 * went quiet weeks after an install, with nothing saying why, is the
 * failure this exists to prevent.
 *
 * Drawing the notification is Firebase's job, not this class's: a message
 * with a `notification` block is drawn by the system when the app is in
 * the background, which is the case that matters. `onMessageReceived`
 * only runs when the app is open, and then the screen is already showing
 * the task list.
 */
class Push : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val session = sessionOf(this) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { MtgApi().registerDevice(session, token, android.os.Build.MODEL) }
                .onFailure { Log.w(TAG, "could not register the new push token: ${it.message}") }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Nothing to do while the app is open. Left deliberately empty
        // rather than absent, so the next person does not wonder whether
        // a case was missed.
        Log.d(TAG, "push while open: ${message.data}")
    }

    companion object {
        private const val TAG = "MtgPush"

        /**
         * The signed-in session, read straight out of the same
         * preferences the activity writes.
         *
         * A service has no activity and no `AppState`, so it cannot be
         * handed the token — and registering without one would land the
         * device on nobody's account. No session means no push, which is
         * right: a signed-out phone has nothing to be told about.
         */
        fun sessionOf(context: Context): String? =
            PrefsStore(context.getSharedPreferences("mtg", Context.MODE_PRIVATE))
                .get(AdminToken.KEY)
                ?.takeIf { it.isNotBlank() }

        /** The channel a task notification lands in. */
        const val CHANNEL = "tasks"

        /**
         * Android 8 and up drop a notification whose channel does not
         * exist, silently. Made at startup rather than at send time:
         * there is no send time on this side.
         */
        fun ensureChannel(context: Context) {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a task finishes, ships, or needs you."
                },
            )
        }
    }
}
