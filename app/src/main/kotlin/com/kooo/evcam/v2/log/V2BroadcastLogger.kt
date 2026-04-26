package com.kooo.evcam.v2.log

import android.content.Intent
import android.os.Bundle

object V2BroadcastLogger {
    fun logReceive(tag: String, intent: Intent?) {
        V2AppLog.i(tag, "receive ${describe(intent)}")
    }

    fun logServiceStart(tag: String, intent: Intent?, flags: Int, startId: Int) {
        V2AppLog.i(tag, "onStartCommand flags=$flags startId=$startId ${describe(intent)}")
    }

    private fun describe(intent: Intent?): String {
        if (intent == null) return "intent=null"
        return buildString {
            append("action=").append(intent.action ?: "null")
            append(" data=").append(intent.dataString ?: "null")
            append(" categories=").append(intent.categories?.joinToString(prefix = "[", postfix = "]") ?: "[]")
            append(" flags=0x").append(intent.flags.toString(16))
            append(" extras=").append(describeExtras(intent.extras))
        }
    }

    @Suppress("DEPRECATION")
    private fun describeExtras(extras: Bundle?): String {
        if (extras == null || extras.isEmpty) return "{}"
        return extras.keySet().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            val value = runCatching { extras.get(key) }.getOrNull()
            "$key=${safeValue(value)}"
        }
    }

    private fun safeValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> value.take(120)
            is Number, is Boolean -> value.toString()
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]", limit = 8) { safeValue(it) }
            is IntArray -> value.joinToString(prefix = "[", postfix = "]", limit = 8)
            is LongArray -> value.joinToString(prefix = "[", postfix = "]", limit = 8)
            is BooleanArray -> value.joinToString(prefix = "[", postfix = "]", limit = 8)
            else -> value.javaClass.simpleName
        }
    }
}
