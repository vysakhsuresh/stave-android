package com.layerbit.stave.brand

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Company-wide links, the same destinations Still, Sheaf, Deja and Abhyas resolve through.
 *
 * Opening any of these does not contradict the no-internet guarantee: the app hands a URL to
 * another app through an Intent and that app does the fetching under its own permissions.
 * Nothing about the user's sound travels with the intent.
 */
object BrandLinks {
    const val WEBSITE_URL = "https://layerbit.co.in"
    const val BRAND_LABEL = "Layerbit AI"
    const val COFFEE_URL = "https://www.buymeacoffee.com/layerbit"
    const val WHATSAPP_URL = "https://wa.me/916282595823"
    const val SUPPORT_EMAIL = "ceo@layerbit.co.in"
    const val PRIVACY_URL = "https://layerbit.co.in/stave/privacy"
    const val SOURCE_URL = "https://github.com/vysakhsuresh/stave-android"
    const val PLAY_LISTING = "https://play.google.com/store/apps/details?id=com.layerbit.stave"

    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // No browser, or WhatsApp not installed: nothing sensible to fall back to.
        }
    }

    fun sendEmail(context: Context, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$SUPPORT_EMAIL")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openUrl(context, WEBSITE_URL)
        }
    }
}
