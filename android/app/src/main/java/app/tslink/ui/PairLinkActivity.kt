package app.tslink.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the pairing link (https://tslink.app/pair#<secret>, App Link) when
 * the phone's camera reads the car's QR code, and hands the secret to the
 * main screen, which asks before pairing. No UI of its own.
 *
 * In-process ([pending]), not an Intent extra: MainActivity is exported (the
 * launcher), and another app could otherwise put a secret it knows there.
 */
class PairLinkActivity : Activity() {
    companion object {
        /** A secret from a link, until the user answers "Pair a car?". */
        @Volatile var pending: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pairing.secretFrom(intent?.dataString)?.let { pending = it }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
