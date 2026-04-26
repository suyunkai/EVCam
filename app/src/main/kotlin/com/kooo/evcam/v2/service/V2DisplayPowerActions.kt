package com.kooo.evcam.v2.service

import android.content.Intent

object V2DisplayPowerActions {
    const val ECARX_DISPLAY_OFF = "ecarx.intent.action.DISPLAY_OFF"
    const val ECARX_DISPLAY_ON = "ecarx.intent.action.DISPLAY_ON"

    fun isDisplayOff(action: String?): Boolean {
        return action == Intent.ACTION_SCREEN_OFF || action == ECARX_DISPLAY_OFF
    }

    fun isDisplayOn(action: String?): Boolean {
        return action == Intent.ACTION_SCREEN_ON || action == ECARX_DISPLAY_ON
    }
}
