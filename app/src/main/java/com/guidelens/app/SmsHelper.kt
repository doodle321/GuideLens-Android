package com.guidelens.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * Emergency SMS. A person who has fallen cannot be expected to find a Send
 * button, so:
 *  1. If SEND_SMS is granted -> send directly via SmsManager (multipart aware).
 *  2. Otherwise -> open the SMS composer prefilled (old behavior), and the
 *     spoken message honestly says which one happened.
 * The result is reported back so the UI never claims a message "sent" when it
 * didn't.
 */
object SmsHelper {

    sealed class SendResult {
        object SentDirectly : SendResult()
        object ComposerOpened : SendResult()
        object NoContact : SendResult()
        object NoSmsApp : SendResult()
        data class Failed(val reason: String) : SendResult()
    }

    fun canSendDirectly(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    // Permission is runtime-guarded by canSendDirectly() above; lint cannot
    // see through the helper, so the false-positive MissingPermission is suppressed.
    @SuppressLint("MissingPermission")
    fun send(activity: Activity, contact: String, message: String): SendResult {
        if (contact.isBlank()) return SendResult.NoContact
        return if (canSendDirectly(activity)) {
            try {
                val mgr = if (Build.VERSION.SDK_INT >= 31) {
                    activity.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                val parts = mgr.divideMessage(message)
                if (parts.size == 1) mgr.sendTextMessage(contact, null, message, null, null)
                else mgr.sendMultipartTextMessage(contact, null, parts, null, null)
                SendResult.SentDirectly
            } catch (e: SecurityException) {
                openComposer(activity, contact, message)
            } catch (e: Exception) {
                SendResult.Failed(e.message ?: "SMS failed")
            }
        } else {
            openComposer(activity, contact, message)
        }
    }

    private fun openComposer(activity: Activity, contact: String, message: String): SendResult {
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:$contact"))
            i.putExtra("sms_body", message)
            activity.startActivity(i)
            SendResult.ComposerOpened
        } catch (e: Exception) {
            SendResult.NoSmsApp
        }
    }
}
