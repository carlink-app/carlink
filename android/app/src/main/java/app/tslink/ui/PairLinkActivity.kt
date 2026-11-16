package app.tslink.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the pairing link (https://tslink.app/pair#<secret>, App Link) when
 * the phone's camera reads the car's QR code, and hands the secret to the
 * main screen, which asks before pairing. No UI of its own.
 */
class PairLinkActivity : Activity() {
    companion object {
        const val EXTRA_SECRET = "app.tslink.PAIR_SECRET"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val secret = Pairing.secretFrom(intent?.dataString)
        val main = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (secret != null) main.putExtra(EXTRA_SECRET, secret)
        startActivity(main)
        finish()
    }
}
