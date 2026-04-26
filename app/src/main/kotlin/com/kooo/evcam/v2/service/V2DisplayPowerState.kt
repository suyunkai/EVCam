package com.kooo.evcam.v2.service

object V2DisplayPowerState {
    @Volatile private var latestDisplayPowerOn: Boolean? = null

    fun initialValue(systemInteractive: Boolean): Boolean {
        return latestDisplayPowerOn ?: systemInteractive
    }

    fun updateFromAction(action: String?): Boolean? {
        val displayPowerOn = when {
            V2DisplayPowerActions.isDisplayOff(action) -> false
            V2DisplayPowerActions.isDisplayOn(action) -> true
            else -> return null
        }
        latestDisplayPowerOn = displayPowerOn
        return displayPowerOn
    }
}
