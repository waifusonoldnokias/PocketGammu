package com.pocketgammu.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import com.pocketgammu.core.nokia.sms.SckTextEncoder
import com.pocketgammu.core.nokia.sms.SmartMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Sends a Smart Message as one or more `//SCKL` text SMS.
 * Each part is a separate ≤160-character SMS; the receiving phone reassembles them
 * using the reference/total/sequence fields in the header.
 */
class SckSmsSender(context: Context) {
    private val app = context.applicationContext

    fun preview(message: SmartMessage): List<String> = SckTextEncoder.encode(message, reference = 0)

    suspend fun send(recipient: String, message: SmartMessage): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val number = recipient.filter { it.isDigit() || it == '+' }
            require(number.length >= 3) { "Enter a valid phone number" }
            val parts = SckTextEncoder.encode(message, reference = Random.nextInt(1, 256))
            val sms = smsManager()
            parts.forEach { text ->
                sms.sendTextMessage(number, null, text, null, null)
                delay(PART_GAP_MS) // keep parts in order on slow networks
            }
            parts.size
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) app.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()

    private companion object { const val PART_GAP_MS = 700L }
}
