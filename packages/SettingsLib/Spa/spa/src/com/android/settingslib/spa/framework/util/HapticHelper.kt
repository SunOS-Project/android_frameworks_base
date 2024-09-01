/*
 * Copyright (C) 2024-2025 The SunOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settingslib.spa.framework.util

import android.os.VibrationAttributes
import android.os.VibrationExtInfo
import android.view.View
import androidx.compose.runtime.Composable

private val VIBRATION_ATTRIBUTES_SWITCH =
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_CUSTOM_SWITCH)

@Composable
fun wrapOnSwitchWithHaptic(view: View, onSwitch: ((checked: Boolean) -> Unit)?): ((checked: Boolean) -> Unit)? {
    if (onSwitch == null) return null
    return {
        view.performHapticFeedbackExt(VibrationExtInfo.Builder().apply {
            setEffectId(VibrationExtInfo.SWITCH_TOGGLE)
            setFallbackEffectId(VibrationExtInfo.CLICK)
            setVibrationAttributes(VIBRATION_ATTRIBUTES_SWITCH)
        }.build())
        onSwitch(it)
    }
}
