/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import static org.sun.os.CustomVibrationAttributes.VIBRATION_ATTRIBUTES_FINGERPRINT_UNLOCK;

import static vendor.sun.hardware.vibratorExt.Effect.CLICK;
import static vendor.sun.hardware.vibratorExt.Effect.DOUBLE_CLICK;
import static vendor.sun.hardware.vibratorExt.Effect.UNIFIED_ERROR;
import static vendor.sun.hardware.vibratorExt.Effect.UNIFIED_SUCCESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioAttributes;
import android.os.Process;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.VibrationExtInfo;
import android.os.Vibrator;
import android.view.View;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.dagger.SysUISingleton;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;

/**
 * A Helper class that offloads {@link Vibrator} calls to a different thread.
 * {@link Vibrator} makes blocking calls that may cause SysUI to ANR.
 * TODO(b/245528624): Use regular Vibrator instance once new APIs are available.
 */
@SysUISingleton
public class VibratorHelper {

    private final Vibrator mVibrator;
    private static final VibrationAttributes HARDWARE_FEEDBACK_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK);
    public static final VibrationAttributes TOUCH_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH);

    private final Executor mExecutor;

    /**
     * Creates a vibrator helper on a new single threaded {@link Executor}.
     */
    @Inject
    public VibratorHelper(@Nullable Vibrator vibrator) {
        this(vibrator, Executors.newSingleThreadExecutor());
    }

    /**
     * Creates new vibrator helper on a specific {@link Executor}.
     */
    @VisibleForTesting
    public VibratorHelper(@Nullable Vibrator vibrator, Executor executor) {
        mExecutor = executor;
        mVibrator = vibrator;
    }

    /**
     * @see Vibrator#vibrate(long)
     */
    public void vibrate(final int effectId) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() ->
                mVibrator.vibrate(VibrationEffect.get(effectId, false /* fallback */),
                        TOUCH_VIBRATION_ATTRIBUTES));
    }

    /**
     * @see Vibrator#vibrate(int, String, VibrationEffect, String, VibrationAttributes)
     */
    public void vibrate(int uid, String opPkg, @NonNull VibrationEffect vibe,
            String reason, @NonNull VibrationAttributes attributes) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() -> mVibrator.vibrate(uid, opPkg, vibe, reason, attributes));
    }

    /**
     * @see Vibrator#vibrate(VibrationEffect, AudioAttributes)
     */
    public void vibrate(@NonNull VibrationEffect effect, @NonNull AudioAttributes attributes) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() -> mVibrator.vibrate(effect, attributes));
    }

    /**
     * @see Vibrator#vibrate(VibrationEffect)
     */
    public void vibrate(@NotNull VibrationEffect effect) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() -> mVibrator.vibrate(effect));
    }

    /**
     * @see Vibrator#vibrate(VibrationEffect, VibrationAttributes)
     */
    public void vibrate(@NonNull VibrationEffect effect, @NonNull VibrationAttributes attributes) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() -> mVibrator.vibrate(effect, attributes));
    }

    /**
     * @see Vibrator#vibrateExt(VibrationExtInfo)
     */
    public void vibrateExt(@NonNull VibrationExtInfo info) {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(() -> mVibrator.vibrateExt(info));
    }

    /**
     * @see Vibrator#hasVibrator()
     */
    public boolean hasVibrator() {
        return mVibrator != null && mVibrator.hasVibrator();
    }

    /**
     * @see Vibrator#cancel()
     */
    public void cancel() {
        if (!hasVibrator()) {
            return;
        }
        mExecutor.execute(mVibrator::cancel);
    }

    /**
     * Perform vibration when biometric authentication success
     */
    public void vibrateAuthSuccess(String reason) {
        final boolean fromFaceUnlock = reason.toLowerCase().contains("face");
        if (fromFaceUnlock) {
            return;
        }
        vibrateExt(new VibrationExtInfo.Builder()
                .setEffectId(UNIFIED_SUCCESS)
                .setFallbackEffectId(CLICK)
                .setReason(reason)
                .setVibrationAttributes(VIBRATION_ATTRIBUTES_FINGERPRINT_UNLOCK)
                .build());
    }

    /**
     * Perform vibration when biometric authentication error
     */
    public void vibrateAuthError(String reason) {
        final boolean fromFaceUnlock = reason.toLowerCase().contains("face");
        if (fromFaceUnlock) {
            return;
        }
        vibrateExt(new VibrationExtInfo.Builder()
                .setEffectId(UNIFIED_ERROR)
                .setFallbackEffectId(DOUBLE_CLICK)
                .setReason(reason)
                .setVibrationAttributes(HARDWARE_FEEDBACK_ATTRIBUTES)
                .build());
    }

    /**
     * @see Vibrator#getPrimitiveDurations(int...)
     */
    public int[] getPrimitiveDurations(int... primitiveIds) {
        return mVibrator.getPrimitiveDurations(primitiveIds);
    }

    /**
     * Perform a vibration using a view and the one-way API with flags
     * @see View#performHapticFeedback(int feedbackConstant, int flags)
     */
    public void performHapticFeedback(@NonNull View view, int feedbackConstant, int flags) {
        view.performHapticFeedback(feedbackConstant, flags);
    }

    /**
     * Perform a vibration using a view and the one-way API
     * @see View#performHapticFeedback(int feedbackConstant)
     */
    public void performHapticFeedback(@NonNull View view, int feedbackConstant) {
        view.performHapticFeedback(feedbackConstant);
    }

    /**
     * Perform a vibration using a view and the one-way API
     * @see View#performHapticFeedbackExt(VibrationExtInfo info)
     */
    public void performHapticFeedbackExt(@NonNull View view, @NonNull VibrationExtInfo info) {
        view.performHapticFeedbackExt(info);
    }
}
