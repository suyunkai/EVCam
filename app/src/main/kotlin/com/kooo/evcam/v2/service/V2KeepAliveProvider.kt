package com.kooo.evcam.v2.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2KeepAliveSettings

class V2KeepAliveProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context ?: return false
        runCatching {
            V2AppLog.init(context)
            V2AppLog.i(TAG, "onCreate early init")
            if (!V2KeepAliveSettings.isKeepAliveEnabled(context)) {
                V2AppLog.i(TAG, "early init skipped: keep alive disabled")
                return false
            }
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({ V2KeepAliveReceiver.registerTimeTick(context) }, REGISTER_TICK_DELAY_MS)
            handler.postDelayed({ V2KeepAliveStarter.requestStart(context, "provider_early_init", preferActivity = false) }, SERVICE_DELAY_MS)
            V2KeepAliveScheduler.schedule(context)
        }.onFailure { error ->
            runCatching { V2AppLog.e(TAG, "early init failed", error) }
        }
        return false
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        private const val TAG = "V2KeepAliveProvider"
        private const val SERVICE_DELAY_MS = 1_000L
        private const val REGISTER_TICK_DELAY_MS = 2_000L
    }
}
