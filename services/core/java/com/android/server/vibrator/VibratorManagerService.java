/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.vibrator;

import static android.os.VibrationEffect.VibrationParameter.targetAmplitude;
import static android.os.VibrationEffect.VibrationParameter.targetFrequency;

import static vendor.sun.hardware.vibratorExt.Effect.CLICK;
import static vendor.sun.hardware.vibratorExt.Effect.HEAVY_CLICK;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.vibrator.IVibrator;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.ExternalVibration;
import android.os.ExternalVibrationScale;
import android.os.Handler;
import android.os.IBinder;
import android.os.IExternalVibratorService;
import android.os.IVibratorManagerService;
import android.os.IVibratorStateListener;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.RichTapVibrationEffect;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.os.vibrator.VibratorInfoFactory;
import android.os.vibrator.persistence.ParsedVibration;
import android.os.vibrator.persistence.VibrationXmlParser;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.SystemService;

import libcore.util.NativeAllocationRegistry;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import org.sun.os.VibratorExtManager;
import org.sun.server.vibrator.CustomVibrationSettings;
import org.sun.server.vibrator.VibrationEffectAdapter;
import org.sun.server.vibrator.VibrationEffectAdapter.CustomEffect;

import vendor.aac.hardware.richtap.vibrator.IRichtapCallback;

/** System implementation of {@link IVibratorManagerService}. */
public class VibratorManagerService extends IVibratorManagerService.Stub {
    private static final String TAG = "VibratorManagerService";
    private static final String EXTERNAL_VIBRATOR_SERVICE = "external_vibrator_service";
    private static final String VIBRATOR_CONTROL_SERVICE =
            "android.frameworks.vibrator.IVibratorControlService/default";
    private static final boolean DEBUG = false;
    private static final VibrationAttributes DEFAULT_ATTRIBUTES =
            new VibrationAttributes.Builder().build();
    private static final int ATTRIBUTES_ALL_BYPASS_FLAGS =
            VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY
                    | VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF
                    | VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE;

    /** Fixed large duration used to note repeating vibrations to {@link IBatteryStats}. */
    private static final long BATTERY_STATS_REPEATING_VIBRATION_DURATION = 5_000;

    /**
     * Maximum millis to wait for a vibration thread cancellation to "clean up" and finish, when
     * blocking for an external vibration. In practice, this should be plenty.
     */
    private static final long VIBRATION_CANCEL_WAIT_MILLIS = 5000;

    /** Lifecycle responsible for initializing this class at the right system server phases. */
    public static class Lifecycle extends SystemService {
        private VibratorManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new VibratorManagerService(getContext(), new Injector());
            publishBinderService(Context.VIBRATOR_MANAGER_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
                mService.systemReady();
            }
        }
    }

    private final Object mLock = new Object();
    private final Context mContext;
    private final Injector mInjector;
    private final PowerManager.WakeLock mWakeLock;
    private final IBatteryStats mBatteryStatsService;
    private final VibratorFrameworkStatsLogger mFrameworkStatsLogger;
    private final Handler mHandler;
    private final VibrationThread mVibrationThread;
    private final AppOpsManager mAppOps;
    private final NativeWrapper mNativeWrapper;
    private final VibratorManagerRecords mVibratorManagerRecords;
    private final long mCapabilities;
    private final int[] mVibratorIds;
    private final SparseArray<VibratorController> mVibrators;
    private final VibrationThreadCallbacks mVibrationThreadCallbacks =
            new VibrationThreadCallbacks();
    @GuardedBy("mLock")
    private final SparseArray<AlwaysOnVibration> mAlwaysOnEffects = new SparseArray<>();
    @GuardedBy("mLock")
    private VibrationStepConductor mCurrentVibration;
    @GuardedBy("mLock")
    private VibrationStepConductor mNextVibration;
    @GuardedBy("mLock")
    private ExternalVibrationHolder mCurrentExternalVibration;
    @GuardedBy("mLock")
    private boolean mServiceReady;

    private final class RichtapCallback extends IRichtapCallback.Stub {
        @Override
        public void onCallback(int result) {
            if (DEBUG) {
                Slog.d(TAG, "RichTap onCallback, result: " + result);
            }
        }

        @Override
        public int getInterfaceVersion() {
            return 1;
        }

        @Override
        public String getInterfaceHash() {
            return "aac_richtap";
        }
    };
    private final IRichtapCallback mRichtapAidlCallback = new RichtapCallback();
    private final RichTapVibratorService mRichTapService = new RichTapVibratorService(mRichtapAidlCallback);

    private final VibratorExtManager mVibratorExtManager = VibratorExtManager.getInstance();

    private final VibrationSettings mVibrationSettings;
    private final VibrationScaler mVibrationScaler;
    private final VibratorControlService mVibratorControlService;
    private final InputDeviceDelegate mInputDeviceDelegate;
    private final DeviceAdapter mDeviceAdapter;

    @GuardedBy("mLock")
    @Nullable private VibratorInfo mCombinedVibratorInfo;
    @GuardedBy("mLock")
    @Nullable private HapticFeedbackVibrationProvider mHapticFeedbackVibrationProvider;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                synchronized (mLock) {
                    // When the system is entering a non-interactive state, we want to cancel
                    // vibrations in case a misbehaving app has abandoned them.
                    if (shouldCancelOnScreenOffLocked(mNextVibration)) {
                        clearNextVibrationLocked(
                                new Vibration.EndInfo(Vibration.Status.CANCELLED_BY_SCREEN_OFF));
                    }
                    if (shouldCancelOnScreenOffLocked(mCurrentVibration)) {
                        mCurrentVibration.notifyCancelled(
                                new Vibration.EndInfo(Vibration.Status.CANCELLED_BY_SCREEN_OFF),
                                /* immediate= */ false);
                    }
                }
            } else if (intent.getAction().equals(RichTapVibratorService.ACTION_CHANGE_MODE)) {
                final int mode = intent.getIntExtra("mode", -1);
                Slog.i(TAG, "RichTap, rec ACTION_CHANGE_MODE, mode: " + mode);
                if (mode == -1) {
                    Slog.e(TAG, "RichTap, invalid mode!");
                    return;
                }
                synchronized (mLock) {
                    mRichTapService.richTapSetVibrationMode(mode);
                }
            }
        }
    };

    static native long nativeInit(OnSyncedVibrationCompleteListener listener);

    static native long nativeGetFinalizer();

    static native long nativeGetCapabilities(long nativeServicePtr);

    static native int[] nativeGetVibratorIds(long nativeServicePtr);

    static native boolean nativePrepareSynced(long nativeServicePtr, int[] vibratorIds);

    static native boolean nativeTriggerSynced(long nativeServicePtr, long vibrationId);

    static native void nativeCancelSynced(long nativeServicePtr);

    @VisibleForTesting
    VibratorManagerService(Context context, Injector injector) {
        mContext = context;
        mInjector = injector;
        mHandler = injector.createHandler(Looper.myLooper());
        mFrameworkStatsLogger = injector.getFrameworkStatsLogger(mHandler);

        CustomVibrationSettings.getInstance().init(mContext, mHandler);
        mVibrationSettings = new VibrationSettings(mContext, mHandler);
        mVibrationScaler = new VibrationScaler(mContext, mVibrationSettings);
        mVibratorControlService = new VibratorControlService(mContext,
                injector.createVibratorControllerHolder(), mVibrationScaler, mVibrationSettings,
                mFrameworkStatsLogger, mLock);
        mInputDeviceDelegate = new InputDeviceDelegate(mContext, mHandler);

        VibrationCompleteListener listener = new VibrationCompleteListener(this);
        mNativeWrapper = injector.getNativeWrapper();
        mNativeWrapper.init(listener);

        int recentDumpSizeLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_recentVibrationsDumpSizeLimit);
        int dumpSizeLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_previousVibrationsDumpSizeLimit);
        int dumpAggregationTimeLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer
                        .config_previousVibrationsDumpAggregationTimeMillisLimit);
        mVibratorManagerRecords = new VibratorManagerRecords(
                recentDumpSizeLimit, dumpSizeLimit, dumpAggregationTimeLimit);

        mBatteryStatsService = injector.getBatteryStatsService();

        mAppOps = mContext.getSystemService(AppOpsManager.class);

        PowerManager pm = context.getSystemService(PowerManager.class);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*vibrator*");
        mWakeLock.setReferenceCounted(true);
        mVibrationThread = new VibrationThread(mWakeLock, mVibrationThreadCallbacks);
        mVibrationThread.start();

        // Load vibrator hardware info. The vibrator ids and manager capabilities are loaded only
        // once and assumed unchanged for the lifecycle of this service. Each individual vibrator
        // can still retry loading each individual vibrator hardware spec once more at systemReady.
        mCapabilities = mNativeWrapper.getCapabilities();
        int[] vibratorIds = mNativeWrapper.getVibratorIds();
        if (vibratorIds == null) {
            mVibratorIds = new int[0];
            mVibrators = new SparseArray<>(0);
        } else {
            // Keep original vibrator id order, which might be meaningful.
            mVibratorIds = vibratorIds;
            mVibrators = new SparseArray<>(mVibratorIds.length);
            for (int vibratorId : vibratorIds) {
                mVibrators.put(vibratorId, injector.createVibratorController(vibratorId, listener));
            }
        }

        // Load vibrator adapter, that depends on hardware info.
        mDeviceAdapter = new DeviceAdapter(mVibrationSettings, mVibrators);

        // Reset the hardware to a default state, in case this is a runtime restart instead of a
        // fresh boot.
        mNativeWrapper.cancelSynced();
        for (int i = 0; i < mVibrators.size(); i++) {
            mVibrators.valueAt(i).reset();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(RichTapVibratorService.ACTION_CHANGE_MODE);
        context.registerReceiver(mIntentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        injector.addService(EXTERNAL_VIBRATOR_SERVICE, new ExternalVibratorService());
        if (injector.isServiceDeclared(VIBRATOR_CONTROL_SERVICE)) {
            injector.addService(VIBRATOR_CONTROL_SERVICE, mVibratorControlService);
        }

    }

    /** Finish initialization at boot phase {@link SystemService#PHASE_SYSTEM_SERVICES_READY}. */
    @VisibleForTesting
    void systemReady() {
        Slog.v(TAG, "Initializing VibratorManager service...");
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "systemReady");
        try {
            // Will retry to load each vibrator's info, if any request have failed.
            for (int i = 0; i < mVibrators.size(); i++) {
                mVibrators.valueAt(i).reloadVibratorInfoIfNeeded();
            }

            CustomVibrationSettings.getInstance().onSystemReady();
            mVibrationSettings.onSystemReady();
            mInputDeviceDelegate.onSystemReady();

            mVibrationSettings.addListener(this::updateServiceState);

            // Will update settings and input devices.
            updateServiceState();
        } finally {
            synchronized (mLock) {
                mServiceReady = true;
            }
            Slog.v(TAG, "VibratorManager service initialized");
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public int[] getVibratorIds() {
        return Arrays.copyOf(mVibratorIds, mVibratorIds.length);
    }

    @Override // Binder call
    @Nullable
    public VibratorInfo getVibratorInfo(int vibratorId) {
        final VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return null;
        }
        final VibratorInfo info = controller.getVibratorInfo();
        synchronized (mLock) {
            if (mServiceReady) {
                return info;
            }
        }
        // If the service is not ready and the load was unsuccessful then return null while waiting
        // for the service to be ready. It will retry to load the complete info from the HAL.
        return controller.isVibratorInfoLoadSuccessful() ? info : null;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    @Override // Binder call
    public boolean isVibrating(int vibratorId) {
        isVibrating_enforcePermission();
        VibratorController controller = mVibrators.get(vibratorId);
        return controller != null && controller.isVibrating();
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    @Override // Binder call
    public boolean registerVibratorStateListener(int vibratorId, IVibratorStateListener listener) {
        registerVibratorStateListener_enforcePermission();
        VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return false;
        }
        return controller.registerVibratorStateListener(listener);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VIBRATOR_STATE)
    @Override // Binder call
    public boolean unregisterVibratorStateListener(int vibratorId,
            IVibratorStateListener listener) {
        unregisterVibratorStateListener_enforcePermission();
        VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return false;
        }
        return controller.unregisterVibratorStateListener(listener);
    }

    @Override // Binder call
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
            @Nullable CombinedVibration effect, @Nullable VibrationAttributes attrs) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "setAlwaysOnEffect");
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.VIBRATE_ALWAYS_ON,
                    "setAlwaysOnEffect");

            if (effect == null) {
                synchronized (mLock) {
                    mAlwaysOnEffects.delete(alwaysOnId);
                    onAllVibratorsLocked(v -> {
                        if (v.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                            v.updateAlwaysOn(alwaysOnId, /* effect= */ null);
                        }
                    });
                }
                return true;
            }
            if (!isEffectValid(effect)) {
                return false;
            }
            attrs = fixupVibrationAttributes(attrs, effect);
            synchronized (mLock) {
                SparseArray<PrebakedSegment> effects = fixupAlwaysOnEffectsLocked(effect);
                if (effects == null) {
                    // Invalid effects set in CombinedVibrationEffect, or always-on capability is
                    // missing on individual vibrators.
                    return false;
                }
                AlwaysOnVibration alwaysOnVibration = new AlwaysOnVibration(alwaysOnId,
                        new Vibration.CallerInfo(attrs, uid, Context.DEVICE_ID_DEFAULT, opPkg,
                                null), effects);
                mAlwaysOnEffects.put(alwaysOnId, alwaysOnVibration);
                updateAlwaysOnLocked(alwaysOnVibration);
            }
            return true;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public void vibrate(int uid, int deviceId, String opPkg, @NonNull CombinedVibration effect,
            @Nullable VibrationAttributes attrs, String reason, IBinder token) {
        vibrateWithPermissionCheck(uid, deviceId, opPkg, effect, attrs, reason, token);
    }

    @Override // Binder call
    public void vibrateExt(int uid, int deviceId, String opPkg, int effectId, int fallbackEffectId,
            float amplitude, String reason, @NonNull VibrationAttributes attrs, IBinder token) {
        vibrateExtInternal(uid, deviceId, opPkg, effectId, fallbackEffectId, amplitude, reason, attrs, token);
    }

    @Override // Binder call
    public void performHapticFeedback(int uid, int deviceId, String opPkg, int constant,
            boolean always, String reason, boolean fromIme) {
        // Note that the `performHapticFeedback` method does not take a token argument from the
        // caller, and instead, uses this service as the token. This is to mitigate performance
        // impact that would otherwise be caused due to marshal latency. Haptic feedback effects are
        // short-lived, so we don't need to cancel when the process dies.
        performHapticFeedbackInternal(
                uid, deviceId, opPkg, constant, always, reason, /* token= */ this, fromIme);
    }

    /**
     * An internal-only version of performHapticFeedback that allows the caller access to the
     * {@link HalVibration}.
     * The Vibration is only returned if it is ongoing after this method returns.
     */
    @VisibleForTesting
    @Nullable
    HalVibration performHapticFeedbackInternal(
            int uid, int deviceId, String opPkg, int constant, boolean always, String reason,
            IBinder token, boolean fromIme) {
        HapticFeedbackVibrationProvider hapticVibrationProvider = getHapticVibrationProvider();
        if (hapticVibrationProvider == null) {
            Slog.e(TAG, "performHapticFeedback; haptic vibration provider not ready.");
            return null;
        }
        if (hapticVibrationProvider.isRestrictedHapticFeedback(constant)
                && !hasPermission(android.Manifest.permission.VIBRATE_SYSTEM_CONSTANTS)) {
            Slog.w(TAG, "performHapticFeedback; no permission for system constant " + constant);
            return null;
        }
        VibrationEffect effect = hapticVibrationProvider.getVibrationForHapticFeedback(constant);
        if (effect == null) {
            Slog.w(TAG, "performHapticFeedback; vibration absent for constant " + constant);
            return null;
        }
        CombinedVibration vib = CombinedVibration.createParallel(effect);
        VibrationAttributes attrs =
                hapticVibrationProvider.getVibrationAttributesForHapticFeedback(
                        constant, /* bypassVibrationIntensitySetting= */ always, fromIme);
        reason = "performHapticFeedback(constant=" + constant + "): " + reason;
        VibratorFrameworkStatsLogger.logPerformHapticsFeedbackIfKeyboard(uid, constant);
        return vibrateWithoutPermissionCheck(uid, deviceId, opPkg, vib, attrs, reason, token);
    }

    /**
     * An internal-only version of vibrate that allows the caller access to the
     * {@link HalVibration}.
     * The Vibration is only returned if it is ongoing after this method returns.
     */
    @VisibleForTesting
    @Nullable
    HalVibration vibrateWithPermissionCheck(int uid, int deviceId, String opPkg,
            @NonNull CombinedVibration effect, @Nullable VibrationAttributes attrs,
            String reason, IBinder token) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "vibrate, reason = " + reason);
        try {
            attrs = fixupVibrationAttributes(attrs, effect);
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.VIBRATE, "vibrate");
            return vibrateInternal(uid, deviceId, opPkg, effect, attrs, reason, token);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    HalVibration vibrateWithoutPermissionCheck(int uid, int deviceId, String opPkg,
            @NonNull CombinedVibration effect, @NonNull VibrationAttributes attrs,
            String reason, IBinder token) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "vibrate no perm check, reason = " + reason);
        try {
            return vibrateInternal(uid, deviceId, opPkg, effect, attrs, reason, token);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private HalVibration vibrateInternal(int uid, int deviceId, String opPkg,
            @NonNull CombinedVibration effect, @NonNull VibrationAttributes attrs,
            String reason, IBinder token) {
        if (token == null) {
            Slog.e(TAG, "token must not be null");
            return null;
        }
        enforceUpdateAppOpsStatsPermission(uid);
        if (!isEffectValid(effect)) {
            return null;
        }
        if (!CustomVibrationSettings.getInstance().shouldVibrate(attrs)) {
            return null;
        }
        if (mRichTapService.disposeRichtapEffectParams(effect)) {
            return null;
        }
        CustomEffect customEffect = VibrationEffectAdapter.getCustomEffect(attrs, opPkg, effect, reason);
        effect = customEffect.combinedEffect;
        // Create Vibration.Stats as close to the received request as possible, for tracking.
        HalVibration vib = new HalVibration(token, effect,
                new Vibration.CallerInfo(attrs, uid, deviceId, opPkg, reason));
        vib.customEffectId = customEffect.customEffectId;
        fillVibrationFallbacks(vib, effect);

        if (attrs.isFlagSet(VibrationAttributes.FLAG_INVALIDATE_SETTINGS_CACHE)) {
            // Force update of user settings before checking if this vibration effect should
            // be ignored or scaled.
            mVibrationSettings.update();
        }

        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Starting vibrate for vibration " + vib.id);
            }

            // Check if user settings or DnD is set to ignore this vibration.
            Vibration.EndInfo vibrationEndInfo = shouldIgnoreVibrationLocked(vib.callerInfo, customEffect.isIME);

            // Check if ongoing vibration is more important than this vibration.
            if (vibrationEndInfo == null) {
                vibrationEndInfo = shouldIgnoreVibrationForOngoingLocked(vib);
            }

            // If not ignored so far then try to start this vibration.
            if (vibrationEndInfo == null) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (effect instanceof CombinedVibration.Mono) {
                        final VibrationEffect vibrEffect = ((CombinedVibration.Mono) effect).getEffect();
                        if (!mRichTapService.checkIfRichTapEffect(vibrEffect, reason)) {
                            doStopVibrateLocked(false);
                        }
                    } else {
                        doStopVibrateLocked(false);
                    }
                    if (mCurrentExternalVibration != null) {
                        mCurrentExternalVibration.mute();
                        vib.stats.reportInterruptedAnotherVibration(
                                mCurrentExternalVibration.callerInfo);
                        endExternalVibrateLocked(
                                new Vibration.EndInfo(Vibration.Status.CANCELLED_SUPERSEDED,
                                        vib.callerInfo),
                                /* continueExternalControl= */ false);
                    } else if (mCurrentVibration != null) {
                        if (mCurrentVibration.getVibration().canPipelineWith(vib)) {
                            // Don't cancel the current vibration if it's pipeline-able.
                            // Note that if there is a pending next vibration that can't be
                            // pipelined, it will have already cancelled the current one, so we
                            // don't need to consider it here as well.
                            if (DEBUG) {
                                Slog.d(TAG, "Pipelining vibration " + vib.id);
                            }
                        } else {
                            vib.stats.reportInterruptedAnotherVibration(
                                    mCurrentVibration.getVibration().callerInfo);
                            mCurrentVibration.notifyCancelled(
                                    new Vibration.EndInfo(Vibration.Status.CANCELLED_SUPERSEDED,
                                            vib.callerInfo),
                                    /* immediate= */ false);
                        }
                    }
                    vibrationEndInfo = startVibrationLocked(vib);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            // Ignored or failed to start the vibration, end it and report metrics right away.
            if (vibrationEndInfo != null) {
                endVibrationLocked(vib, vibrationEndInfo, /* shouldWriteStats= */ true);
            }
            return vib;
        }
    }

    private HalVibration vibrateExtInternal(int uid, int deviceId, String opPkg, int effectId,
            int fallbackEffectId, float amplitude, String reason,
            @NonNull VibrationAttributes attrs, IBinder token) {
        if (token == null) {
            Slog.e(TAG, "token must not be null");
            return null;
        }
        if (!mVibratorExtManager.isEffectSupported(effectId)) {
            if (fallbackEffectId < 0) {
                if (effectId >= CLICK && effectId <= HEAVY_CLICK) {
                    fallbackEffectId = effectId;
                } else {
                    return null;
                }
            }
            CombinedVibration effect = CombinedVibration.createParallel(VibrationEffect.get(fallbackEffectId));
            attrs = fixupVibrationAttributes(attrs, effect);
            return vibrateInternal(uid, deviceId, opPkg, effect, attrs, reason, token);
        }
        if (!CustomVibrationSettings.getInstance().shouldVibrate(attrs)) {
            return null;
        }

        final CombinedVibration effect = CombinedVibration.createParallel(
                VibrationEffect.createOneShot(10 /* Mock duration */, Math.max(1, (int) (255 * amplitude))));
        attrs = fixupVibrationAttributes(attrs, effect);

        HalVibration vib = new HalVibration(token, effect,
                new Vibration.CallerInfo(attrs, uid, deviceId, opPkg, reason));
        vib.customEffectId = effectId;
        fillVibrationFallbacks(vib, effect);

        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Starting vibrate for vibration " + vib.id);
            }

            // Check if user settings or DnD is set to ignore this vibration.
            Vibration.EndInfo vibrationEndInfo = shouldIgnoreVibrationLocked(vib.callerInfo, false);

            // Check if ongoing vibration is more important than this vibration.
            if (vibrationEndInfo == null) {
                vibrationEndInfo = shouldIgnoreVibrationForOngoingLocked(vib);
            }

            // If not ignored so far then try to start this vibration.
            if (vibrationEndInfo == null) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    doStopVibrateLocked(false);
                    if (mCurrentExternalVibration != null) {
                        mCurrentExternalVibration.mute();
                        vib.stats.reportInterruptedAnotherVibration(
                                mCurrentExternalVibration.callerInfo);
                        endExternalVibrateLocked(
                                new Vibration.EndInfo(Vibration.Status.CANCELLED_SUPERSEDED,
                                        vib.callerInfo),
                                /* continueExternalControl= */ false);
                    } else if (mCurrentVibration != null) {
                        if (mCurrentVibration.getVibration().canPipelineWith(vib)) {
                            // Don't cancel the current vibration if it's pipeline-able.
                            // Note that if there is a pending next vibration that can't be
                            // pipelined, it will have already cancelled the current one, so we
                            // don't need to consider it here as well.
                            if (DEBUG) {
                                Slog.d(TAG, "Pipelining vibration " + vib.id);
                            }
                        } else {
                            vib.stats.reportInterruptedAnotherVibration(
                                    mCurrentVibration.getVibration().callerInfo);
                            mCurrentVibration.notifyCancelled(
                                    new Vibration.EndInfo(Vibration.Status.CANCELLED_SUPERSEDED,
                                            vib.callerInfo),
                                    /* immediate= */ false);
                        }
                    }
                    vibrationEndInfo = startVibrationLocked(vib);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            // Ignored or failed to start the vibration, end it and report metrics right away.
            if (vibrationEndInfo != null) {
                endVibrationLocked(vib, vibrationEndInfo, /* shouldWriteStats= */ true);
            }
            return vib;
        }
    }

    @Override // Binder call
    public void cancelVibrate(int usageFilter, IBinder token) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "cancelVibrate");
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.VIBRATE,
                    "cancelVibrate");

            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "Canceling vibration");
                }
                Vibration.EndInfo cancelledByUserInfo =
                        new Vibration.EndInfo(Vibration.Status.CANCELLED_BY_USER);
                final long ident = Binder.clearCallingIdentity();
                try {
                    doStopVibrateLocked(true);
                    if (mNextVibration != null
                            && shouldCancelVibration(mNextVibration.getVibration(),
                            usageFilter, token)) {
                        clearNextVibrationLocked(cancelledByUserInfo);
                    }
                    if (mCurrentVibration != null
                            && shouldCancelVibration(mCurrentVibration.getVibration(),
                            usageFilter, token)) {
                        mCurrentVibration.notifyCancelled(
                                cancelledByUserInfo, /* immediate= */false);
                    }
                    if (mCurrentExternalVibration != null
                            && shouldCancelVibration(
                            mCurrentExternalVibration.externalVibration.getVibrationAttributes(),
                            usageFilter)) {
                        mCurrentExternalVibration.mute();
                        endExternalVibrateLocked(
                                cancelledByUserInfo, /* continueExternalControl= */ false);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        final long ident = Binder.clearCallingIdentity();

        boolean isDumpProto = false;
        for (String arg : args) {
            if (arg.equals("--proto")) {
                isDumpProto = true;
            }
        }
        try {
            if (isDumpProto) {
                dumpProto(fd);
            } else {
                dumpText(pw);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void dumpText(PrintWriter w) {
        if (DEBUG) {
            Slog.d(TAG, "Dumping vibrator manager service to text...");
        }
        IndentingPrintWriter pw = new IndentingPrintWriter(w, /* singleIndent= */ "  ");
        synchronized (mLock) {
            pw.println("VibratorManagerService:");
            pw.increaseIndent();

            mVibrationSettings.dump(pw);
            pw.println();

            mVibrationScaler.dump(pw);
            pw.println();

            pw.println("Vibrators:");
            pw.increaseIndent();
            for (int i = 0; i < mVibrators.size(); i++) {
                mVibrators.valueAt(i).dump(pw);
            }
            pw.decreaseIndent();
            pw.println();

            pw.println("CurrentVibration:");
            pw.increaseIndent();
            if (mCurrentVibration != null) {
                mCurrentVibration.getVibration().getDebugInfo().dump(pw);
            } else {
                pw.println("null");
            }
            pw.decreaseIndent();
            pw.println();

            pw.println("NextVibration:");
            pw.increaseIndent();
            if (mNextVibration != null) {
                mNextVibration.getVibration().getDebugInfo().dump(pw);
            } else {
                pw.println("null");
            }
            pw.decreaseIndent();
            pw.println();

            pw.println("CurrentExternalVibration:");
            pw.increaseIndent();
            if (mCurrentExternalVibration != null) {
                mCurrentExternalVibration.getDebugInfo().dump(pw);
            } else {
                pw.println("null");
            }
            pw.decreaseIndent();
        }

        pw.println();
        pw.println();
        mVibratorManagerRecords.dump(pw);

        pw.println();
        pw.println();
        mVibratorControlService.dump(pw);
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        if (DEBUG) {
            Slog.d(TAG, "Dumping vibrator manager service to proto...");
        }
        synchronized (mLock) {
            mVibrationSettings.dump(proto);
            mVibrationScaler.dump(proto);
            if (mCurrentVibration != null) {
                mCurrentVibration.getVibration().getDebugInfo().dump(proto,
                        VibratorManagerServiceDumpProto.CURRENT_VIBRATION);
            }
            if (mCurrentExternalVibration != null) {
                mCurrentExternalVibration.getDebugInfo().dump(proto,
                        VibratorManagerServiceDumpProto.CURRENT_EXTERNAL_VIBRATION);
            }

            boolean isVibrating = false;
            boolean isUnderExternalControl = false;
            for (int i = 0; i < mVibrators.size(); i++) {
                proto.write(VibratorManagerServiceDumpProto.VIBRATOR_IDS, mVibrators.keyAt(i));
                isVibrating |= mVibrators.valueAt(i).isVibrating();
                isUnderExternalControl |= mVibrators.valueAt(i).isUnderExternalControl();
            }
            proto.write(VibratorManagerServiceDumpProto.IS_VIBRATING, isVibrating);
            proto.write(VibratorManagerServiceDumpProto.VIBRATOR_UNDER_EXTERNAL_CONTROL,
                    isUnderExternalControl);
        }
        mVibratorManagerRecords.dump(proto);
        mVibratorControlService.dump(proto);
        proto.flush();
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback cb, ResultReceiver resultReceiver) {
        new VibratorManagerShellCommand(cb.getShellCallbackBinder())
                .exec(this, in, out, err, args, cb, resultReceiver);
    }

    @VisibleForTesting
    void updateServiceState() {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Updating device state...");
            }
            boolean inputDevicesChanged = mInputDeviceDelegate.updateInputDeviceVibrators(
                    mVibrationSettings.shouldVibrateInputDevices());

            for (int i = 0; i < mAlwaysOnEffects.size(); i++) {
                updateAlwaysOnLocked(mAlwaysOnEffects.valueAt(i));
            }

            if (mCurrentVibration == null) {
                return;
            }

            HalVibration vib = mCurrentVibration.getVibration();
            Vibration.EndInfo vibrationEndInfo = shouldIgnoreVibrationLocked(vib.callerInfo,
                    VibrationEffectAdapter.isIMEPackage(vib.callerInfo.opPkg));

            if (inputDevicesChanged || (vibrationEndInfo != null)) {
                if (DEBUG) {
                    Slog.d(TAG, "Canceling vibration because settings changed: "
                            + (inputDevicesChanged ? "input devices changed"
                            : vibrationEndInfo.status));
                }
                mCurrentVibration.notifyCancelled(
                        new Vibration.EndInfo(Vibration.Status.CANCELLED_BY_SETTINGS_UPDATE),
                        /* immediate= */ false);
            }
        }
    }

    private void setExternalControl(boolean externalControl, VibrationStats vibrationStats) {
        for (int i = 0; i < mVibrators.size(); i++) {
            mVibrators.valueAt(i).setExternalControl(externalControl);
            vibrationStats.reportSetExternalControl();
        }
    }

    @GuardedBy("mLock")
    private void updateAlwaysOnLocked(AlwaysOnVibration vib) {
        for (int i = 0; i < vib.effects.size(); i++) {
            VibratorController vibrator = mVibrators.get(vib.effects.keyAt(i));
            PrebakedSegment effect = vib.effects.valueAt(i);
            if (vibrator == null) {
                continue;
            }
            Vibration.EndInfo vibrationEndInfo = shouldIgnoreVibrationLocked(vib.callerInfo, false);
            if (vibrationEndInfo == null) {
                effect = mVibrationScaler.scale(effect, vib.callerInfo.attrs.getUsage());
            } else {
                // Vibration should not run, use null effect to remove registered effect.
                effect = null;
            }
            vibrator.updateAlwaysOn(vib.alwaysOnId, effect);
        }
    }

    private void doVibratorOnEnvelope(int[] relativeTime, int[] scaleArr, int[] freqArr,
            boolean steepMode, int amplitude, int uid, VibrationAttributes attrs) {
        synchronized (mRichTapService) {
            mRichTapService.richTapVibratorOnEnvelope(
                    relativeTime, scaleArr, freqArr, steepMode, amplitude);
        }
    }

    private long doVibratorOnPatternHe(VibrationEffect effect, int uid) {
        synchronized (mRichTapService) {
            mRichTapService.richTapVibratorOnPatternHe(effect);
            return 0;
        }
    }

    private void doStopVibrateLocked(boolean force) {
        synchronized (mRichTapService) {
            if (DEBUG) {
                Slog.d(TAG, "RichTap, time before vibratorStop: " + System.currentTimeMillis());
            }
            mRichTapService.richTapVibratorStop(force);
            if (DEBUG) {
                Slog.d(TAG, "RichTap, time after vibratorStop: " + System.currentTimeMillis());
            }
        }
    }

    private long doVibratorOnExtPrebakedEffectLocked(VibrationEffect effect) {
        final RichTapVibrationEffect.ExtPrebaked prebaked = (RichTapVibrationEffect.ExtPrebaked) effect;
        mRichTapService.richTapVibratorSetAmplitude(VibrationEffect.MAX_AMPLITUDE);
        mRichTapService.richTapVibratorPerform((int) prebaked.getId(), (byte) prebaked.getScale());
        return 0;
    }

    @GuardedBy("mLock")
    @Nullable
    private Vibration.EndInfo startVibrationLocked(HalVibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "startVibrationLocked");
        try {
            final CombinedVibration combEffect = vib.getEffectToPlay();
            if (combEffect instanceof CombinedVibration.Mono) {
                VibrationEffect vibrEffect = ((CombinedVibration.Mono) combEffect).getEffect();
                if (vibrEffect instanceof RichTapVibrationEffect.ExtPrebaked) {
                    doVibratorOnExtPrebakedEffectLocked(vibrEffect);
                    return new Vibration.EndInfo(Vibration.Status.RUNNING, vib.callerInfo);
                }
                if (vibrEffect instanceof RichTapVibrationEffect.Envelope) {
                    final RichTapVibrationEffect.Envelope envelope = (RichTapVibrationEffect.Envelope) vibrEffect;
                    doVibratorOnEnvelope(envelope.getRelativeTimeArr(), envelope.getScaleArr(), envelope.getFreqArr(),
                            envelope.isSteepMode(), envelope.getAmplitude(), vib.callerInfo.uid, vib.callerInfo.attrs);
                            return new Vibration.EndInfo(Vibration.Status.RUNNING, vib.callerInfo);
                }
                if (vibrEffect instanceof RichTapVibrationEffect.PatternHe) {
                    final RichTapVibrationEffect.PatternHe patternHe = (RichTapVibrationEffect.PatternHe) vibrEffect;
                    Slog.d(TAG, "RichTap, play he");
                    doVibratorOnPatternHe(patternHe, vib.callerInfo.uid);
                    return new Vibration.EndInfo(Vibration.Status.RUNNING, vib.callerInfo);
                }
            }

            if (mInputDeviceDelegate.isAvailable()) {
                return startVibrationOnInputDevicesLocked(vib);
            }

            VibrationStepConductor conductor = createVibrationStepConductor(vib);

            if (mCurrentVibration == null) {
                return startVibrationOnThreadLocked(conductor);
            }
            // If there's already a vibration queued (waiting for the previous one to finish
            // cancelling), end it cleanly and replace it with the new one.
            // Note that we don't consider pipelining here, because new pipelined ones should
            // replace pending non-executing pipelined ones anyway.
            clearNextVibrationLocked(
                    new Vibration.EndInfo(Vibration.Status.IGNORED_SUPERSEDED, vib.callerInfo));
            mNextVibration = conductor;
            return null;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private Vibration.EndInfo startVibrationOnThreadLocked(VibrationStepConductor conductor) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "startVibrationThreadLocked");
        try {
            HalVibration vib = conductor.getVibration();
            int mode = startAppOpModeLocked(vib.callerInfo);
            switch (mode) {
                case AppOpsManager.MODE_ALLOWED:
                    Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                    // Make sure mCurrentVibration is set while triggering the VibrationThread.
                    mCurrentVibration = conductor;
                    if (!mVibrationThread.runVibrationOnVibrationThread(mCurrentVibration)) {
                        // Shouldn't happen. The method call already logs a wtf.
                        mCurrentVibration = null;  // Aborted.
                        return new Vibration.EndInfo(Vibration.Status.IGNORED_ERROR_SCHEDULING);
                    }
                    return null;
                case AppOpsManager.MODE_ERRORED:
                    Slog.w(TAG, "Start AppOpsManager operation errored for uid "
                            + vib.callerInfo.uid);
                    return new Vibration.EndInfo(Vibration.Status.IGNORED_ERROR_APP_OPS);
                default:
                    return new Vibration.EndInfo(Vibration.Status.IGNORED_APP_OPS);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private void endVibrationLocked(HalVibration vib, Vibration.EndInfo vibrationEndInfo,
            boolean shouldWriteStats) {
        vib.end(vibrationEndInfo);
        logAndRecordVibration(vib.getDebugInfo());
        if (shouldWriteStats) {
            mFrameworkStatsLogger.writeVibrationReportedAsync(
                    vib.getStatsInfo(/* completionUptimeMillis= */ SystemClock.uptimeMillis()));
        }
    }

    @GuardedBy("mLock")
    private void endVibrationAndWriteStatsLocked(ExternalVibrationHolder vib,
            Vibration.EndInfo vibrationEndInfo) {
        vib.end(vibrationEndInfo);
        logAndRecordVibration(vib.getDebugInfo());
        mFrameworkStatsLogger.writeVibrationReportedAsync(
                vib.getStatsInfo(/* completionUptimeMillis= */ SystemClock.uptimeMillis()));
    }

    private VibrationStepConductor createVibrationStepConductor(HalVibration vib) {
        CompletableFuture<Void> requestVibrationParamsFuture = null;

        if (Flags.adaptiveHapticsEnabled() && !vib.callerInfo.attrs.isFlagSet(
                VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE)
                && mVibratorControlService.shouldRequestVibrationParams(
                vib.callerInfo.attrs.getUsage())) {
            requestVibrationParamsFuture =
                    mVibratorControlService.triggerVibrationParamsRequest(
                            vib.callerInfo.uid, vib.callerInfo.attrs.getUsage(),
                            mVibrationSettings.getRequestVibrationParamsTimeoutMs());
        }

        return new VibrationStepConductor(vib, mVibrationSettings, mDeviceAdapter, mVibrationScaler,
                mFrameworkStatsLogger, requestVibrationParamsFuture, mVibrationThreadCallbacks);
    }

    private Vibration.EndInfo startVibrationOnInputDevicesLocked(HalVibration vib) {
        if (!vib.callerInfo.attrs.isFlagSet(
                VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE)) {
            // Scale resolves the default amplitudes from the effect before scaling them.
            vib.scaleEffects(mVibrationScaler);
        } else {
            vib.resolveEffects(mVibrationScaler.getDefaultVibrationAmplitude());
        }
        mInputDeviceDelegate.vibrateIfAvailable(vib.callerInfo, vib.getEffectToPlay());

        return new Vibration.EndInfo(Vibration.Status.FORWARDED_TO_INPUT_DEVICES);
    }

    private void logAndRecordVibration(Vibration.DebugInfo info) {
        info.logMetrics(mFrameworkStatsLogger);
        logVibrationStatus(info.mCallerInfo.uid, info.mCallerInfo.attrs, info.mStatus);
        mVibratorManagerRecords.record(info);
    }

    private void logVibrationStatus(int uid, VibrationAttributes attrs,
            Vibration.Status status) {
        switch (status) {
            case IGNORED_BACKGROUND:
                Slog.e(TAG, "Ignoring incoming vibration as process with"
                        + " uid= " + uid + " is background," + " attrs= " + attrs);
                break;
            case IGNORED_ERROR_APP_OPS:
                Slog.w(TAG, "Would be an error: vibrate from uid " + uid);
                break;
            case IGNORED_FOR_EXTERNAL:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration for current external vibration");
                }
                break;
            case IGNORED_FOR_HIGHER_IMPORTANCE:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration in favor of ongoing vibration"
                            + " with higher importance");
                }
                break;
            case IGNORED_FOR_ONGOING:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration in favor of repeating vibration");
                }
                break;
            case IGNORED_FOR_RINGER_MODE:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration because of ringer mode, attrs="
                            + attrs);
                }
                break;
            case IGNORED_FROM_VIRTUAL_DEVICE:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration because it came from a virtual"
                            + " device, attrs= " + attrs);
                }
                break;
            default:
                if (DEBUG) {
                    Slog.d(TAG, "Vibration for uid=" + uid + " and with attrs=" + attrs
                            + " ended with status " + status);
                }
        }
    }

    @GuardedBy("mLock")
    private void reportFinishedVibrationLocked(Vibration.EndInfo vibrationEndInfo) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "reportFinishVibrationLocked");
        Trace.asyncTraceEnd(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
        try {
            HalVibration vib = mCurrentVibration.getVibration();
            if (DEBUG) {
                Slog.d(TAG, "Reporting vibration " + vib.id + " finished with "
                        + vibrationEndInfo);
            }
            // DO NOT write metrics at this point, wait for the VibrationThread to report the
            // vibration was released, after all cleanup. The metrics will be reported then.
            endVibrationLocked(vib, vibrationEndInfo, /* shouldWriteStats= */ false);
            finishAppOpModeLocked(vib.callerInfo);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private void onSyncedVibrationComplete(long vibrationId) {
        synchronized (mLock) {
            if (mCurrentVibration != null
                    && mCurrentVibration.getVibration().id == vibrationId) {
                if (DEBUG) {
                    Slog.d(TAG, "Synced vibration " + vibrationId + " complete, notifying thread");
                }
                mCurrentVibration.notifySyncedVibrationComplete();
            }
        }
    }

    private void onVibrationComplete(int vibratorId, long vibrationId) {
        synchronized (mLock) {
            if (mCurrentVibration != null
                    && mCurrentVibration.getVibration().id == vibrationId) {
                if (DEBUG) {
                    Slog.d(TAG, "Vibration " + vibrationId + " on vibrator " + vibratorId
                            + " complete, notifying thread");
                }
                mCurrentVibration.notifyVibratorComplete(vibratorId);
            }
        }
    }

    /**
     * Check if given vibration should be ignored by this service because of the ongoing vibration.
     *
     * @return a Vibration.EndInfo if the vibration should be ignored, null otherwise.
     */
    @GuardedBy("mLock")
    @Nullable
    private Vibration.EndInfo shouldIgnoreVibrationForOngoingLocked(Vibration vib) {
        if (mCurrentExternalVibration != null) {
            return shouldIgnoreVibrationForOngoing(vib, mCurrentExternalVibration);
        }

        if (mNextVibration != null) {
            Vibration.EndInfo vibrationEndInfo = shouldIgnoreVibrationForOngoing(vib,
                    mNextVibration.getVibration());
            if (vibrationEndInfo != null) {
                // Next vibration has higher importance than the new one, so the new vibration
                // should be ignored.
                return vibrationEndInfo;
            }
        }

        if (mCurrentVibration != null) {
            HalVibration currentVibration = mCurrentVibration.getVibration();
            if (currentVibration.hasEnded() || mCurrentVibration.wasNotifiedToCancel()) {
                // Current vibration has ended or is cancelling, should not block incoming
                // vibrations.
                return null;
            }

            return shouldIgnoreVibrationForOngoing(vib, currentVibration);
        }

        return null;
    }

    /**
     * Checks if the ongoing vibration has higher importance than the new one. If they have similar
     * importance, then {@link Vibration#isRepeating()} is used as a tiebreaker.
     *
     * @return a Vibration.EndInfo if the vibration should be ignored, null otherwise.
     */
    @Nullable
    private static Vibration.EndInfo shouldIgnoreVibrationForOngoing(
            @NonNull Vibration newVibration, @NonNull Vibration ongoingVibration) {

        int newVibrationImportance = getVibrationImportance(newVibration);
        int ongoingVibrationImportance = getVibrationImportance(ongoingVibration);

        if (newVibrationImportance > ongoingVibrationImportance) {
            // New vibration has higher importance and should not be ignored.
            return null;
        }

        if (ongoingVibrationImportance > newVibrationImportance) {
            // Existing vibration has higher importance and should not be cancelled.
            return new Vibration.EndInfo(Vibration.Status.IGNORED_FOR_HIGHER_IMPORTANCE,
                    ongoingVibration.callerInfo);
        }

        // Same importance, use repeating as a tiebreaker.
        if (ongoingVibration.isRepeating() && !newVibration.isRepeating()) {
            // Ongoing vibration is repeating and new one is not, give priority to ongoing
            return new Vibration.EndInfo(Vibration.Status.IGNORED_FOR_ONGOING,
                    ongoingVibration.callerInfo);
        }
        // New vibration is repeating or this is a complete tie between them,
        // give priority to new vibration.
        return null;
    }

    /**
     * Gets the vibration importance based on usage. In the case where usage is unknown, it maps
     * repeating vibrations to ringtones and non-repeating vibrations to touches.
     *
     * @return a numeric representation for the vibration importance, larger values represent a
     * higher importance
     */
    private static int getVibrationImportance(Vibration vibration) {
        int usage = vibration.callerInfo.attrs.getUsage();
        if (usage == VibrationAttributes.USAGE_UNKNOWN) {
            if (vibration.isRepeating()) {
                usage = VibrationAttributes.USAGE_RINGTONE;
            } else {
                usage = VibrationAttributes.USAGE_TOUCH;
            }
        }

        switch (usage) {
            case VibrationAttributes.USAGE_RINGTONE:
                return 5;
            case VibrationAttributes.USAGE_ALARM:
                return 4;
            case VibrationAttributes.USAGE_NOTIFICATION:
                return 3;
            case VibrationAttributes.USAGE_COMMUNICATION_REQUEST:
            case VibrationAttributes.USAGE_ACCESSIBILITY:
                return 2;
            case VibrationAttributes.USAGE_HARDWARE_FEEDBACK:
            case VibrationAttributes.USAGE_PHYSICAL_EMULATION:
                return 1;
            case VibrationAttributes.USAGE_MEDIA:
            case VibrationAttributes.USAGE_TOUCH:
            default:
                return 0;
        }
    }

    /**
     * Check if given vibration should be ignored by this service.
     *
     * @return a Vibration.EndInfo if the vibration should be ignored, null otherwise.
     */
    @GuardedBy("mLock")
    @Nullable
    private Vibration.EndInfo shouldIgnoreVibrationLocked(Vibration.CallerInfo callerInfo,
            boolean isImePackage) {
        Vibration.Status statusFromSettings = mVibrationSettings.shouldIgnoreVibration(callerInfo,
                isImePackage && CustomVibrationSettings.getInstance().shouldForceEnableIMEHaptic());
        if (statusFromSettings != null) {
            return new Vibration.EndInfo(statusFromSettings);
        }

        int mode = checkAppOpModeLocked(callerInfo);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            if (mode == AppOpsManager.MODE_ERRORED) {
                // We might be getting calls from within system_server, so we don't actually
                // want to throw a SecurityException here.
                return new Vibration.EndInfo(Vibration.Status.IGNORED_ERROR_APP_OPS);
            } else {
                return new Vibration.EndInfo(Vibration.Status.IGNORED_APP_OPS);
            }
        }

        return null;
    }

    /**
     * Return true if the vibration has the same token and usage belongs to given usage class.
     *
     * @param vib         The ongoing or pending vibration to be cancelled.
     * @param usageFilter The vibration usages to be cancelled, any bitwise combination of
     *                    VibrationAttributes.USAGE_* values.
     * @param token       The binder token to identify the vibration origin. Only vibrations
     *                    started with the same token can be cancelled with it.
     */
    private boolean shouldCancelVibration(HalVibration vib, int usageFilter, IBinder token) {
        return (vib.callerToken == token) && shouldCancelVibration(vib.callerInfo.attrs,
                usageFilter);
    }

    /**
     * Return true if the external vibration usage belongs to given usage class.
     *
     * @param attrs       The attributes of an ongoing or pending vibration to be cancelled.
     * @param usageFilter The vibration usages to be cancelled, any bitwise combination of
     *                    VibrationAttributes.USAGE_* values.
     */
    private boolean shouldCancelVibration(VibrationAttributes attrs, int usageFilter) {
        if (attrs.getUsage() == VibrationAttributes.USAGE_UNKNOWN) {
            // Special case, usage UNKNOWN would match all filters. Instead it should only match if
            // it's cancelling that usage specifically, or if cancelling all usages.
            return usageFilter == VibrationAttributes.USAGE_UNKNOWN
                    || usageFilter == VibrationAttributes.USAGE_FILTER_MATCH_ALL;
        }
        return (usageFilter & attrs.getUsage()) == attrs.getUsage();
    }

    /**
     * Check which mode should be set for a vibration with given {@code uid}, {@code opPkg} and
     * {@code attrs}. This will return one of the AppOpsManager.MODE_*.
     */
    @GuardedBy("mLock")
    private int checkAppOpModeLocked(Vibration.CallerInfo callerInfo) {
        int mode = mAppOps.checkAudioOpNoThrow(AppOpsManager.OP_VIBRATE,
                callerInfo.attrs.getAudioUsage(), callerInfo.uid, callerInfo.opPkg);
        int fixedMode = fixupAppOpModeLocked(mode, callerInfo.attrs);
        if (mode != fixedMode && fixedMode == AppOpsManager.MODE_ALLOWED) {
            // If we're just ignoring the vibration op then this is set by DND and we should ignore
            // if we're asked to bypass. AppOps won't be able to record this operation, so make
            // sure we at least note it in the logs for debugging.
            Slog.d(TAG, "Bypassing DND for vibrate from uid " + callerInfo.uid);
        }
        return fixedMode;
    }

    /** Start an operation in {@link AppOpsManager}, if allowed. */
    @GuardedBy("mLock")
    private int startAppOpModeLocked(Vibration.CallerInfo callerInfo) {
        return fixupAppOpModeLocked(
                mAppOps.startOpNoThrow(AppOpsManager.OP_VIBRATE, callerInfo.uid, callerInfo.opPkg),
                callerInfo.attrs);
    }

    /**
     * Finish a previously started operation in {@link AppOpsManager}. This will be a noop if no
     * operation with same uid was previously started.
     */
    @GuardedBy("mLock")
    private void finishAppOpModeLocked(Vibration.CallerInfo callerInfo) {
        mAppOps.finishOp(AppOpsManager.OP_VIBRATE, callerInfo.uid, callerInfo.opPkg);
    }

    /**
     * Enforces {@link android.Manifest.permission#UPDATE_APP_OPS_STATS} to incoming UID if it's
     * different from the calling UID.
     */
    private void enforceUpdateAppOpsStatsPermission(int uid) {
        if (uid == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    /**
     * Validate the incoming {@link CombinedVibration}.
     *
     * We can't throw exceptions here since we might be called from some system_server component,
     * which would bring the whole system down.
     *
     * @return whether the CombinedVibrationEffect is non-null and valid
     */
    private static boolean isEffectValid(@Nullable CombinedVibration effect) {
        if (effect == null) {
            Slog.wtf(TAG, "effect must not be null");
            return false;
        }
        try {
            effect.validate();
        } catch (Exception e) {
            Slog.wtf(TAG, "Encountered issue when verifying CombinedVibrationEffect.", e);
            return false;
        }
        return true;
    }

    /**
     * Sets fallback effects to all prebaked ones in given combination of effects, based on {@link
     * VibrationSettings#getFallbackEffect}.
     */
    private void fillVibrationFallbacks(HalVibration vib, CombinedVibration effect) {
        if (effect instanceof CombinedVibration.Mono) {
            fillVibrationFallbacks(vib, ((CombinedVibration.Mono) effect).getEffect());
        } else if (effect instanceof CombinedVibration.Stereo) {
            SparseArray<VibrationEffect> effects =
                    ((CombinedVibration.Stereo) effect).getEffects();
            for (int i = 0; i < effects.size(); i++) {
                fillVibrationFallbacks(vib, effects.valueAt(i));
            }
        } else if (effect instanceof CombinedVibration.Sequential) {
            List<CombinedVibration> effects =
                    ((CombinedVibration.Sequential) effect).getEffects();
            for (int i = 0; i < effects.size(); i++) {
                fillVibrationFallbacks(vib, effects.get(i));
            }
        }
    }

    private void fillVibrationFallbacks(HalVibration vib, VibrationEffect effect) {
        if (effect instanceof RichTapVibrationEffect.ExtPrebaked ||
                effect instanceof RichTapVibrationEffect.Envelope ||
                effect instanceof RichTapVibrationEffect.PatternHe) {
            return;
        }
        VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
        int segmentCount = composed.getSegments().size();
        for (int i = 0; i < segmentCount; i++) {
            VibrationEffectSegment segment = composed.getSegments().get(i);
            if (segment instanceof PrebakedSegment) {
                PrebakedSegment prebaked = (PrebakedSegment) segment;
                VibrationEffect fallback = mVibrationSettings.getFallbackEffect(
                        prebaked.getEffectId());
                if (prebaked.shouldFallback() && fallback != null) {
                    vib.addFallback(prebaked.getEffectId(), fallback);
                }
            }
        }
    }

    /**
     * Return new {@link VibrationAttributes} that only applies flags that this user has permissions
     * to use.
     */
    @NonNull
    private VibrationAttributes fixupVibrationAttributes(@Nullable VibrationAttributes attrs,
            @Nullable CombinedVibration effect) {
        if (attrs == null) {
            attrs = DEFAULT_ATTRIBUTES;
        }
        int usage = attrs.getUsage();
        if ((usage == VibrationAttributes.USAGE_UNKNOWN)
                && (effect != null) && effect.isHapticFeedbackCandidate()) {
            usage = VibrationAttributes.USAGE_TOUCH;
        }
        int flags = attrs.getFlags();
        if ((flags & ATTRIBUTES_ALL_BYPASS_FLAGS) != 0) {
            if (!(hasPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    || hasPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                    || hasPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING))) {
                // Remove bypass flags from attributes if the app does not have permissions.
                flags &= ~ATTRIBUTES_ALL_BYPASS_FLAGS;
            }
        }
        if ((usage == attrs.getUsage()) && (flags == attrs.getFlags())) {
            return attrs;
        }
        return new VibrationAttributes.Builder(attrs)
                .setUsage(usage)
                .setFlags(flags, attrs.getFlags())
                .build();
    }

    @GuardedBy("mLock")
    @Nullable
    private SparseArray<PrebakedSegment> fixupAlwaysOnEffectsLocked(
            CombinedVibration effect) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "fixupAlwaysOnEffectsLocked");
        try {
            SparseArray<VibrationEffect> effects;
            if (effect instanceof CombinedVibration.Mono) {
                VibrationEffect syncedEffect = ((CombinedVibration.Mono) effect).getEffect();
                effects = transformAllVibratorsLocked(unused -> syncedEffect);
            } else if (effect instanceof CombinedVibration.Stereo) {
                effects = ((CombinedVibration.Stereo) effect).getEffects();
            } else {
                // Only synced combinations can be used for always-on effects.
                return null;
            }
            SparseArray<PrebakedSegment> result = new SparseArray<>();
            for (int i = 0; i < effects.size(); i++) {
                PrebakedSegment prebaked = extractPrebakedSegment(effects.valueAt(i));
                if (prebaked == null) {
                    Slog.e(TAG, "Only prebaked effects supported for always-on.");
                    return null;
                }
                int vibratorId = effects.keyAt(i);
                VibratorController vibrator = mVibrators.get(vibratorId);
                if (vibrator != null && vibrator.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                    result.put(vibratorId, prebaked);
                }
            }
            if (result.size() == 0) {
                return null;
            }
            return result;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Nullable
    private static PrebakedSegment extractPrebakedSegment(VibrationEffect effect) {
        if (effect instanceof VibrationEffect.Composed) {
            VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
            if (composed.getSegments().size() == 1) {
                VibrationEffectSegment segment = composed.getSegments().get(0);
                if (segment instanceof PrebakedSegment) {
                    return (PrebakedSegment) segment;
                }
            }
        }
        return null;
    }

    /**
     * Check given mode, one of the AppOpsManager.MODE_*, against {@link VibrationAttributes} to
     * allow bypassing {@link AppOpsManager} checks.
     */
    @GuardedBy("mLock")
    private int fixupAppOpModeLocked(int mode, VibrationAttributes attrs) {
        if (mode == AppOpsManager.MODE_IGNORED
                && attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY)) {
            return AppOpsManager.MODE_ALLOWED;
        }
        return mode;
    }

    private boolean hasPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    @GuardedBy("mLock")
    private boolean shouldCancelOnScreenOffLocked(@Nullable VibrationStepConductor conductor) {
        if (conductor == null) {
            return false;
        }
        HalVibration vib = conductor.getVibration();
        return mVibrationSettings.shouldCancelVibrationOnScreenOff(vib.callerInfo,
                vib.stats.getCreateUptimeMillis());
    }

    @GuardedBy("mLock")
    private void onAllVibratorsLocked(Consumer<VibratorController> consumer) {
        for (int i = 0; i < mVibrators.size(); i++) {
            consumer.accept(mVibrators.valueAt(i));
        }
    }

    @GuardedBy("mLock")
    private <T> SparseArray<T> transformAllVibratorsLocked(Function<VibratorController, T> fn) {
        SparseArray<T> ret = new SparseArray<>(mVibrators.size());
        for (int i = 0; i < mVibrators.size(); i++) {
            ret.put(mVibrators.keyAt(i), fn.apply(mVibrators.valueAt(i)));
        }
        return ret;
    }

    /** Point of injection for test dependencies */
    @VisibleForTesting
    static class Injector {

        NativeWrapper getNativeWrapper() {
            return new NativeWrapper();
        }

        Handler createHandler(Looper looper) {
            return new Handler(looper);
        }

        IBatteryStats getBatteryStatsService() {
            return IBatteryStats.Stub.asInterface(ServiceManager.getService(
                    BatteryStats.SERVICE_NAME));
        }

        VibratorFrameworkStatsLogger getFrameworkStatsLogger(Handler handler) {
            return new VibratorFrameworkStatsLogger(handler);
        }

        VibratorController createVibratorController(int vibratorId,
                VibratorController.OnVibrationCompleteListener listener) {
            return new VibratorController(vibratorId, listener);
        }

        HapticFeedbackVibrationProvider createHapticFeedbackVibrationProvider(
                Resources resources, VibratorInfo vibratorInfo) {
            return new HapticFeedbackVibrationProvider(resources, vibratorInfo);
        }

        void addService(String name, IBinder service) {
            ServiceManager.addService(name, service);
        }

        VibratorControllerHolder createVibratorControllerHolder() {
            return new VibratorControllerHolder();
        }

        boolean isServiceDeclared(String name) {
            return ServiceManager.isDeclared(name);
        }
    }

    /**
     * Implementation of {@link VibrationThread.VibratorManagerHooks} that controls synced
     * vibrations and reports them when finished.
     */
    private final class VibrationThreadCallbacks implements VibrationThread.VibratorManagerHooks {

        @Override
        public boolean prepareSyncedVibration(long requiredCapabilities, int[] vibratorIds) {
            if ((mCapabilities & requiredCapabilities) != requiredCapabilities) {
                // This sync step requires capabilities this device doesn't have, skipping sync...
                return false;
            }
            return mNativeWrapper.prepareSynced(vibratorIds);
        }

        @Override
        public boolean triggerSyncedVibration(long vibrationId) {
            return mNativeWrapper.triggerSynced(vibrationId);
        }

        @Override
        public void cancelSyncedVibration() {
            mNativeWrapper.cancelSynced();
        }

        @Override
        public void noteVibratorOn(int uid, long duration) {
            try {
                if (duration <= 0) {
                    // Tried to turn vibrator ON and got:
                    // duration == 0: Unsupported effect/method or zero-amplitude segment.
                    // duration < 0: Unexpected error triggering the vibrator.
                    // Skip battery stats and atom metric for VibratorStageChanged to ON.
                    return;
                }
                if (duration == Long.MAX_VALUE) {
                    // Repeating duration has started. Report a fixed duration here, noteVibratorOff
                    // should be called when this is cancelled.
                    duration = BATTERY_STATS_REPEATING_VIBRATION_DURATION;
                }
                mBatteryStatsService.noteVibratorOn(uid, duration);
                mFrameworkStatsLogger.writeVibratorStateOnAsync(uid, duration);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error logging VibratorStateChanged to ON", e);
            }
        }

        @Override
        public void noteVibratorOff(int uid) {
            try {
                mBatteryStatsService.noteVibratorOff(uid);
                mFrameworkStatsLogger.writeVibratorStateOffAsync(uid);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error logging VibratorStateChanged to OFF", e);
            }
        }

        @Override
        public void onVibrationCompleted(long vibrationId, Vibration.EndInfo vibrationEndInfo) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration " + vibrationId + " finished with " + vibrationEndInfo);
            }
            synchronized (mLock) {
                if (mCurrentVibration != null
                        && mCurrentVibration.getVibration().id == vibrationId) {
                    reportFinishedVibrationLocked(vibrationEndInfo);
                }
            }
        }

        @Override
        public void onVibrationThreadReleased(long vibrationId) {
            if (DEBUG) {
                Slog.d(TAG, "VibrationThread released after finished vibration");
            }
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "Processing VibrationThread released callback");
                }
                if (Build.IS_DEBUGGABLE && mCurrentVibration != null
                        && mCurrentVibration.getVibration().id != vibrationId) {
                    Slog.wtf(TAG, TextUtils.formatSimple(
                            "VibrationId mismatch on release. expected=%d, released=%d",
                            mCurrentVibration.getVibration().id, vibrationId));
                }
                if (mCurrentVibration != null) {
                    // This is when we consider the current vibration complete, so report metrics.
                    mFrameworkStatsLogger.writeVibrationReportedAsync(
                            mCurrentVibration.getVibration().getStatsInfo(
                                    /* completionUptimeMillis= */ SystemClock.uptimeMillis()));
                    mCurrentVibration = null;
                }
                if (mNextVibration != null) {
                    VibrationStepConductor nextConductor = mNextVibration;
                    mNextVibration = null;
                    Vibration.EndInfo vibrationEndInfo = startVibrationOnThreadLocked(
                            nextConductor);
                    if (vibrationEndInfo != null) {
                        // Failed to start the vibration, end it and report metrics right away.
                        endVibrationLocked(nextConductor.getVibration(),
                                vibrationEndInfo, /* shouldWriteStats= */ true);
                    }
                }
            }
        }
    }

    /** Listener for synced vibration completion callbacks from native. */
    @VisibleForTesting
    interface OnSyncedVibrationCompleteListener {

        /** Callback triggered when synced vibration is complete. */
        void onComplete(long vibrationId);
    }

    /**
     * Implementation of listeners to native vibrators with a weak reference to this service.
     */
    private static final class VibrationCompleteListener implements
            VibratorController.OnVibrationCompleteListener, OnSyncedVibrationCompleteListener {
        private WeakReference<VibratorManagerService> mServiceRef;

        VibrationCompleteListener(VibratorManagerService service) {
            mServiceRef = new WeakReference<>(service);
        }

        @Override
        public void onComplete(long vibrationId) {
            VibratorManagerService service = mServiceRef.get();
            if (service != null) {
                service.onSyncedVibrationComplete(vibrationId);
            }
        }

        @Override
        public void onComplete(int vibratorId, long vibrationId) {
            VibratorManagerService service = mServiceRef.get();
            if (service != null) {
                service.onVibrationComplete(vibratorId, vibrationId);
            }
        }
    }

    /**
     * Combination of prekabed vibrations on multiple vibrators, with the same {@link
     * VibrationAttributes}, that can be set for always-on effects.
     */
    private static final class AlwaysOnVibration {
        public final int alwaysOnId;
        public final Vibration.CallerInfo callerInfo;
        public final SparseArray<PrebakedSegment> effects;

        AlwaysOnVibration(int alwaysOnId, Vibration.CallerInfo callerInfo,
                SparseArray<PrebakedSegment> effects) {
            this.alwaysOnId = alwaysOnId;
            this.callerInfo = callerInfo;
            this.effects = effects;
        }
    }

    /** Holder for a {@link ExternalVibration}. */
    private final class ExternalVibrationHolder extends Vibration implements
            IBinder.DeathRecipient {

        public final ExternalVibration externalVibration;
        public ExternalVibrationScale scale = new ExternalVibrationScale();

        private Vibration.Status mStatus;

        private ExternalVibrationHolder(ExternalVibration externalVibration) {
            super(externalVibration.getToken(), new Vibration.CallerInfo(
                    externalVibration.getVibrationAttributes(), externalVibration.getUid(),
                    // TODO(b/249785241): Find a way to link ExternalVibration to a VirtualDevice
                    // instead of using DEVICE_ID_INVALID here and relying on the UID checks.
                    Context.DEVICE_ID_INVALID, externalVibration.getPackage(), null));
            this.externalVibration = externalVibration;
            mStatus = Vibration.Status.RUNNING;
        }

        public void scale(VibrationScaler scaler, int usage) {
            scale.scaleLevel = scaler.getScaleLevel(usage);
            scale.adaptiveHapticsScale = scaler.getAdaptiveHapticsScale(usage);
            stats.reportAdaptiveScale(scale.adaptiveHapticsScale);
        }

        public void mute() {
            externalVibration.mute();
        }

        public void linkToDeath() {
            externalVibration.linkToDeath(this);
        }

        public void unlinkToDeath() {
            externalVibration.unlinkToDeath(this);
        }

        public boolean isHoldingSameVibration(ExternalVibration externalVibration) {
            return this.externalVibration.equals(externalVibration);
        }

        public void end(Vibration.EndInfo info) {
            if (mStatus != Vibration.Status.RUNNING) {
                // Already ended, ignore this call
                return;
            }
            mStatus = info.status;
            stats.reportEnded(info.endedBy);

            if (stats.hasStarted()) {
                // External vibration doesn't have feedback from total time the vibrator was playing
                // with non-zero amplitude, so we use the duration between start and end times of
                // the vibration as the time the vibrator was ON, since the haptic channels are
                // open for this duration and can receive vibration waveform data.
                stats.reportVibratorOn(
                        stats.getEndUptimeMillis() - stats.getStartUptimeMillis());
            }
        }

        public void binderDied() {
            synchronized (mLock) {
                if (mCurrentExternalVibration != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "External vibration finished because binder died");
                    }
                    endExternalVibrateLocked(
                            new Vibration.EndInfo(Vibration.Status.CANCELLED_BINDER_DIED),
                            /* continueExternalControl= */ false);
                }
            }
        }

        public Vibration.DebugInfo getDebugInfo() {
            return new Vibration.DebugInfo(mStatus, stats, /* playedEffect= */ null,
                    /* originalEffect= */ null, scale.scaleLevel, scale.adaptiveHapticsScale,
                    callerInfo);
        }

        public VibrationStats.StatsInfo getStatsInfo(long completionUptimeMillis) {
            return new VibrationStats.StatsInfo(
                    externalVibration.getUid(),
                    FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__EXTERNAL,
                    externalVibration.getVibrationAttributes().getUsage(), mStatus, stats,
                    completionUptimeMillis);
        }

        @Override
        boolean isRepeating() {
            // We don't currently know if the external vibration is repeating, so we just use a
            // heuristic based on the usage. Ideally this would be propagated in the
            // ExternalVibration.
            int usage = externalVibration.getVibrationAttributes().getUsage();
            return usage == VibrationAttributes.USAGE_RINGTONE
                    || usage == VibrationAttributes.USAGE_ALARM;
        }
    }

    /** Wrapper around the static-native methods of {@link VibratorManagerService} for tests. */
    @VisibleForTesting
    public static class NativeWrapper {

        private long mNativeServicePtr = 0;

        /** Returns native pointer to newly created controller and connects with HAL service. */
        public void init(OnSyncedVibrationCompleteListener listener) {
            mNativeServicePtr = nativeInit(listener);
            long finalizerPtr = nativeGetFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorManagerService.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativeServicePtr);
            }
        }

        /** Returns manager capabilities. */
        public long getCapabilities() {
            return nativeGetCapabilities(mNativeServicePtr);
        }

        /** Returns vibrator ids. */
        public int[] getVibratorIds() {
            return nativeGetVibratorIds(mNativeServicePtr);
        }

        /** Prepare vibrators for triggering vibrations in sync. */
        public boolean prepareSynced(@NonNull int[] vibratorIds) {
            return nativePrepareSynced(mNativeServicePtr, vibratorIds);
        }

        /** Trigger prepared synced vibration. */
        public boolean triggerSynced(long vibrationId) {
            return nativeTriggerSynced(mNativeServicePtr, vibrationId);
        }

        /** Cancel prepared synced vibration. */
        public void cancelSynced() {
            nativeCancelSynced(mNativeServicePtr);
        }
    }

    /** Keep records of vibrations played and provide debug information for this service. */
    private static final class VibratorManagerRecords {
        private final VibrationRecords mAggregatedVibrationHistory;
        private final VibrationRecords mRecentVibrations;

        VibratorManagerRecords(int recentVibrationSizeLimit, int aggregationSizeLimit,
                int aggregationTimeLimit) {
            mAggregatedVibrationHistory =
                    new VibrationRecords(aggregationSizeLimit, aggregationTimeLimit);
            // Recent vibrations are not aggregated, to help debugging issues that just happened.
            mRecentVibrations =
                    new VibrationRecords(recentVibrationSizeLimit, /* aggregationTimeLimit= */ 0);
        }

        synchronized void record(Vibration.DebugInfo info) {
            GroupedAggregatedLogRecords.AggregatedLogRecord<VibrationRecord> droppedRecord =
                    mRecentVibrations.add(new VibrationRecord(info));
            if (droppedRecord != null) {
                // Move dropped record from recent list to aggregated history list.
                mAggregatedVibrationHistory.add(droppedRecord.getLatest());
            }
        }

        synchronized void dump(IndentingPrintWriter pw) {
            pw.println("Recent vibrations:");
            pw.increaseIndent();
            mRecentVibrations.dump(pw);
            pw.decreaseIndent();

            pw.println();
            pw.println();
            pw.println("Aggregated vibration history:");
            pw.increaseIndent();
            mAggregatedVibrationHistory.dump(pw);
            pw.decreaseIndent();
        }

        synchronized void dump(ProtoOutputStream proto) {
            mRecentVibrations.dump(proto);
        }
    }

    /** Keep records of vibrations played and provide debug information for this service. */
    private static final class VibrationRecords
            extends GroupedAggregatedLogRecords<VibrationRecord> {

        VibrationRecords(int sizeLimit, int aggregationTimeLimit) {
            super(sizeLimit, aggregationTimeLimit);
        }

        @Override
        void dumpGroupHeader(IndentingPrintWriter pw, int usage) {
            pw.println(VibrationAttributes.usageToString(usage) + ":");
        }

        @Override
        long findGroupKeyProtoFieldId(int usage) {
            return switch (usage) {
                case VibrationAttributes.USAGE_RINGTONE ->
                    VibratorManagerServiceDumpProto.PREVIOUS_RING_VIBRATIONS;
                case VibrationAttributes.USAGE_NOTIFICATION ->
                    VibratorManagerServiceDumpProto.PREVIOUS_NOTIFICATION_VIBRATIONS;
                case VibrationAttributes.USAGE_ALARM ->
                    VibratorManagerServiceDumpProto.PREVIOUS_ALARM_VIBRATIONS;
                default ->
                    VibratorManagerServiceDumpProto.PREVIOUS_VIBRATIONS;
            };
        }
    }

    /**
     * Record for a single {@link Vibration.DebugInfo}, that can be grouped by usage and aggregated
     * by UID, {@link VibrationAttributes} and {@link VibrationEffect}.
     */
    private static final class VibrationRecord
            implements GroupedAggregatedLogRecords.SingleLogRecord {
        private final Vibration.DebugInfo mInfo;

        VibrationRecord(Vibration.DebugInfo info) {
            mInfo = info;
        }

        @Override
        public int getGroupKey() {
            return mInfo.mCallerInfo.attrs.getUsage();
        }

        @Override
        public long getCreateUptimeMs() {
            return mInfo.mCreateTime;
        }

        @Override
        public boolean mayAggregate(GroupedAggregatedLogRecords.SingleLogRecord record) {
            if (!(record instanceof VibrationRecord)) {
                return false;
            }
            Vibration.DebugInfo info = ((VibrationRecord) record).mInfo;
            return mInfo.mCallerInfo.uid == info.mCallerInfo.uid
                    && Objects.equals(mInfo.mCallerInfo.attrs, info.mCallerInfo.attrs)
                    && Objects.equals(mInfo.mPlayedEffect, info.mPlayedEffect);
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            // Prints a compact version of each vibration request for dumpsys.
            mInfo.dumpCompact(pw);
        }

        @Override
        public void dump(ProtoOutputStream proto, long fieldId) {
            mInfo.dump(proto, fieldId);
        }
    }

    /** Clears mNextVibration if set, ending it cleanly */
    @GuardedBy("mLock")
    private void clearNextVibrationLocked(Vibration.EndInfo vibrationEndInfo) {
        if (mNextVibration != null) {
            if (DEBUG) {
                Slog.d(TAG, "Dropping pending vibration " + mNextVibration.getVibration().id
                        + " with end info: " + vibrationEndInfo);
            }
            // Clearing next vibration before playing it, end it and report metrics right away.
            endVibrationLocked(mNextVibration.getVibration(), vibrationEndInfo,
                    /* shouldWriteStats= */ true);
            mNextVibration = null;
        }
    }

    /**
     * Ends the external vibration, and clears related service state.
     *
     * @param vibrationEndInfo        the status and related info to end the associated Vibration
     * @param continueExternalControl indicates whether external control will continue. If not, the
     *                                HAL will have external control turned off.
     */
    @GuardedBy("mLock")
    private void endExternalVibrateLocked(Vibration.EndInfo vibrationEndInfo,
            boolean continueExternalControl) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "endExternalVibrateLocked");
        try {
            if (mCurrentExternalVibration == null) {
                return;
            }
            mCurrentExternalVibration.unlinkToDeath();
            if (!continueExternalControl) {
                setExternalControl(false, mCurrentExternalVibration.stats);
            }
            // The external control was turned off, end it and report metrics right away.
            endVibrationAndWriteStatsLocked(mCurrentExternalVibration, vibrationEndInfo);
            mCurrentExternalVibration = null;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private HapticFeedbackVibrationProvider getHapticVibrationProvider() {
        synchronized (mLock) {
            // Used a cached haptic vibration provider if one exists.
            if (mHapticFeedbackVibrationProvider != null) {
                return mHapticFeedbackVibrationProvider;
            }
            VibratorInfo combinedVibratorInfo = getCombinedVibratorInfo();
            if (combinedVibratorInfo == null) {
                return null;
            }
            return mHapticFeedbackVibrationProvider =
                    mInjector.createHapticFeedbackVibrationProvider(
                            mContext.getResources(), combinedVibratorInfo);
        }
    }

    private VibratorInfo getCombinedVibratorInfo() {
        synchronized (mLock) {
            // Used a cached resolving vibrator if one exists.
            if (mCombinedVibratorInfo != null) {
                return mCombinedVibratorInfo;
            }

            // Return an empty resolving vibrator if the service has no vibrator.
            if (mVibratorIds.length == 0) {
                return mCombinedVibratorInfo = VibratorInfo.EMPTY_VIBRATOR_INFO;
            }

            // Combine the vibrator infos of all the service's vibrator to create a single resolving
            // vibrator that is based on the combined info.
            VibratorInfo[] infos = new VibratorInfo[mVibratorIds.length];
            for (int i = 0; i < mVibratorIds.length; i++) {
                VibratorInfo info = getVibratorInfo(mVibratorIds[i]);
                // If any one of the service's vibrator does not have a valid vibrator info, stop
                // trying to create and cache a combined resolving vibrator. Combine the infos only
                // when infos for all vibrators are available.
                if (info == null) {
                    return null;
                }
                infos[i] = info;
            }

            return mCombinedVibratorInfo = VibratorInfoFactory.create(/* id= */ -1, infos);
        }
    }

    /** Implementation of {@link IExternalVibratorService} to be triggered on external control. */
    @VisibleForTesting
    final class ExternalVibratorService extends IExternalVibratorService.Stub {

        @Override
        public ExternalVibrationScale onExternalVibrationStart(ExternalVibration vib) {
            // Create Vibration.Stats as close to the received request as possible, for tracking.
            ExternalVibrationHolder vibHolder = new ExternalVibrationHolder(vib);
            // Mute the request until we run all the checks and accept the vibration.
            vibHolder.scale.scaleLevel = ExternalVibrationScale.ScaleLevel.SCALE_MUTE;
            boolean alreadyUnderExternalControl = false;
            boolean waitForCompletion = false;

            synchronized (mLock) {
                if (!hasExternalControlCapability()) {
                    endVibrationAndWriteStatsLocked(vibHolder,
                            new Vibration.EndInfo(Vibration.Status.IGNORED_UNSUPPORTED));
                    return vibHolder.scale;
                }

                if (ActivityManager.checkComponentPermission(android.Manifest.permission.VIBRATE,
                        vib.getUid(), -1 /*owningUid*/, true /*exported*/)
                        != PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "pkg=" + vib.getPackage() + ", uid=" + vib.getUid()
                            + " tried to play externally controlled vibration"
                            + " without VIBRATE permission, ignoring.");
                    endVibrationAndWriteStatsLocked(vibHolder,
                            new Vibration.EndInfo(Vibration.Status.IGNORED_MISSING_PERMISSION));
                    return vibHolder.scale;
                }

                Vibration.EndInfo vibrationEndInfo = shouldIgnoreVibrationLocked(
                        vibHolder.callerInfo, false);

                if (vibrationEndInfo == null
                        && mCurrentExternalVibration != null
                        && mCurrentExternalVibration.isHoldingSameVibration(vib)) {
                    // We are already playing this external vibration, so we can return the same
                    // scale calculated in the previous call to this method.
                    return mCurrentExternalVibration.scale;
                }

                if (vibrationEndInfo == null) {
                    // Check if ongoing vibration is more important than this vibration.
                    vibrationEndInfo = shouldIgnoreVibrationForOngoingLocked(vibHolder);
                }

                if (vibrationEndInfo != null) {
                    endVibrationAndWriteStatsLocked(vibHolder, vibrationEndInfo);
                    return vibHolder.scale;
                }

                if (mCurrentExternalVibration == null) {
                    // If we're not under external control right now, then cancel any normal
                    // vibration that may be playing and ready the vibrator for external control.
                    if (mCurrentVibration != null) {
                        vibHolder.stats.reportInterruptedAnotherVibration(
                                mCurrentVibration.getVibration().callerInfo);
                        clearNextVibrationLocked(
                                new Vibration.EndInfo(Vibration.Status.IGNORED_FOR_EXTERNAL,
                                        vibHolder.callerInfo));
                        mCurrentVibration.notifyCancelled(
                                new Vibration.EndInfo(Vibration.Status.CANCELLED_SUPERSEDED,
                                        vibHolder.callerInfo),
                                /* immediate= */ true);
                        waitForCompletion = true;
                    }
                } else {
                    // At this point we have an externally controlled vibration playing already.
                    // Since the interface defines that only one externally controlled vibration can
                    // play at a time, we need to first mute the ongoing vibration and then return
                    // a scale from this function for the new one, so we can be assured that the
                    // ongoing will be muted in favor of the new vibration.
                    //
                    // Note that this doesn't support multiple concurrent external controls, as we
                    // would need to mute the old one still if it came from a different controller.
                    alreadyUnderExternalControl = true;
                    mCurrentExternalVibration.mute();
                    vibHolder.stats.reportInterruptedAnotherVibration(
                            mCurrentExternalVibration.callerInfo);
                    endExternalVibrateLocked(
                            new Vibration.EndInfo(Vibration.Status.CANCELLED_SUPERSEDED,
                                    vibHolder.callerInfo),
                            /* continueExternalControl= */ true);
                }

                VibrationAttributes attrs = fixupVibrationAttributes(vib.getVibrationAttributes(),
                        /* effect= */ null);
                if (attrs.isFlagSet(VibrationAttributes.FLAG_INVALIDATE_SETTINGS_CACHE)) {
                    // Force update of user settings before checking if this vibration effect should
                    // be ignored or scaled.
                    mVibrationSettings.update();
                }

                mCurrentExternalVibration = vibHolder;
                vibHolder.linkToDeath();
                vibHolder.scale(mVibrationScaler, attrs.getUsage());
            }

            if (waitForCompletion) {
                if (!mVibrationThread.waitForThreadIdle(VIBRATION_CANCEL_WAIT_MILLIS)) {
                    Slog.e(TAG, "Timed out waiting for vibration to cancel");
                    synchronized (mLock) {
                        // Trigger endExternalVibrateLocked to unlink to death recipient.
                        endExternalVibrateLocked(
                                new Vibration.EndInfo(Vibration.Status.IGNORED_ERROR_CANCELLING),
                                /* continueExternalControl= */ false);
                        // Mute the request, vibration will be ignored.
                        vibHolder.scale.scaleLevel = ExternalVibrationScale.ScaleLevel.SCALE_MUTE;
                    }
                    return vibHolder.scale;
                }
            }
            if (!alreadyUnderExternalControl) {
                if (DEBUG) {
                    Slog.d(TAG, "Vibrator going under external control.");
                }
                setExternalControl(true, vibHolder.stats);
            }
            if (DEBUG) {
                Slog.d(TAG, "Playing external vibration: " + vib);
            }
            // Vibrator will start receiving data from external channels after this point.
            // Report current time as the vibration start time, for debugging.
            vibHolder.stats.reportStarted();
            return vibHolder.scale;
        }

        @Override
        public void onExternalVibrationStop(ExternalVibration vib) {
            synchronized (mLock) {
                if (mCurrentExternalVibration != null
                        && mCurrentExternalVibration.isHoldingSameVibration(vib)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Stopping external vibration: " + vib);
                    }
                    endExternalVibrateLocked(
                            new Vibration.EndInfo(Vibration.Status.FINISHED),
                            /* continueExternalControl= */ false);
                }
            }
        }

        private boolean hasExternalControlCapability() {
            for (int i = 0; i < mVibrators.size(); i++) {
                if (mVibrators.valueAt(i).hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Provide limited functionality from {@link VibratorManagerService} as shell commands. */
    private final class VibratorManagerShellCommand extends ShellCommand {
        public static final String SHELL_PACKAGE_NAME = "com.android.shell";
        public static final long VIBRATION_END_TIMEOUT_MS = 500; // Clean up shouldn't be too long.

        private final class CommonOptions {
            public boolean force = false;
            public String description = "Shell command";
            public boolean background = false;

            CommonOptions() {
                String nextArg;
                while ((nextArg = peekNextArg()) != null) {
                    switch (nextArg) {
                        case "-f":
                            getNextArgRequired(); // consume "-f"
                            force = true;
                            break;
                        case "-B":
                            getNextArgRequired(); // consume "-B"
                            background = true;
                            break;
                        case "-d":
                            getNextArgRequired(); // consume "-d"
                            description = getNextArgRequired();
                            break;
                        default:
                            // nextArg is not a common option, finish reading.
                            return;
                    }
                }
            }
        }

        private final IBinder mShellCallbacksToken;

        private VibratorManagerShellCommand(IBinder shellCallbacksToken) {
            mShellCallbacksToken = shellCallbacksToken;
        }

        @Override
        public int onCommand(String cmd) {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "onCommand " + cmd);
            try {
                if ("list".equals(cmd)) {
                    return runListVibrators();
                }
                if ("synced".equals(cmd)) {
                    return runMono();
                }
                if ("combined".equals(cmd)) {
                    return runStereo();
                }
                if ("sequential".equals(cmd)) {
                    return runSequential();
                }
                if ("xml".equals(cmd)) {
                    return runXml();
                }
                if ("cancel".equals(cmd)) {
                    return runCancel();
                }
                if ("feedback".equals(cmd)) {
                    return runHapticFeedback();
                }
                return handleDefaultCommands(cmd);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private int runListVibrators() {
            try (PrintWriter pw = getOutPrintWriter();) {
                if (mVibratorIds.length == 0) {
                    pw.println("No vibrator found");
                } else {
                    for (int id : mVibratorIds) {
                        pw.println(id);
                    }
                }
                pw.println("");
                return 0;
            }
        }

        /**
         * Runs a CombinedVibration using the configured common options and attributes.
         */
        private void runVibrate(CommonOptions commonOptions, CombinedVibration combined) {
            VibrationAttributes attrs = createVibrationAttributes(commonOptions);
            // If running in the background, bind to death of the server binder rather than the
            // client, and the cancel command likewise uses the server binder reference to
            // only cancel background vibrations.
            IBinder deathBinder = commonOptions.background ? VibratorManagerService.this
                    : mShellCallbacksToken;
            int uid = Binder.getCallingUid();
            // Resolve the package name for the client based on the process UID, to cover cases like
            // rooted shell clients using ROOT_UID.
            String resolvedPackageName = AppOpsManager.resolvePackageName(uid, SHELL_PACKAGE_NAME);
            HalVibration vib = vibrateWithPermissionCheck(uid, Context.DEVICE_ID_DEFAULT,
                    resolvedPackageName, combined, attrs, commonOptions.description, deathBinder);
            maybeWaitOnVibration(vib, commonOptions);
        }

        private int runMono() {
            runVibrate(new CommonOptions(), CombinedVibration.createParallel(nextEffect()));
            return 0;
        }

        private int runStereo() {
            CommonOptions commonOptions = new CommonOptions();
            CombinedVibration.ParallelCombination combination =
                    CombinedVibration.startParallel();
            while ("-v".equals(getNextOption())) {
                int vibratorId = Integer.parseInt(getNextArgRequired());
                combination.addVibrator(vibratorId, nextEffect());
            }
            runVibrate(commonOptions, combination.combine());
            return 0;
        }

        private int runSequential() {
            CommonOptions commonOptions = new CommonOptions();
            CombinedVibration.SequentialCombination combination =
                    CombinedVibration.startSequential();
            while ("-v".equals(getNextOption())) {
                int vibratorId = Integer.parseInt(getNextArgRequired());
                combination.addNext(vibratorId, nextEffect());
            }
            runVibrate(commonOptions, combination.combine());
            return 0;
        }

        private int runXml() {
            CommonOptions commonOptions = new CommonOptions();
            String xml = getNextArgRequired();
            CombinedVibration vibration = parseXml(xml);
            runVibrate(commonOptions, vibration);
            return 0;
        }

        private int runCancel() {
            // Cancel is only needed if the vibration was run in the background, otherwise it's
            // terminated by the shell command ending. In these cases, the token was that of the
            // service rather than the client.
            cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, VibratorManagerService.this);
            return 0;
        }

        private int runHapticFeedback() {
            CommonOptions commonOptions = new CommonOptions();
            int constant = Integer.parseInt(getNextArgRequired());

            IBinder deathBinder = commonOptions.background ? VibratorManagerService.this
                    : mShellCallbacksToken;
            HalVibration vib = performHapticFeedbackInternal(Binder.getCallingUid(),
                    Context.DEVICE_ID_DEFAULT, SHELL_PACKAGE_NAME, constant,
                    /* always= */ commonOptions.force, /* reason= */ commonOptions.description,
                    deathBinder, false /* fromIme */);
            maybeWaitOnVibration(vib, commonOptions);

            return 0;
        }

        private VibrationEffect nextEffect() {
            VibrationEffect.Composition composition = VibrationEffect.startComposition();
            String nextArg;

            while ((nextArg = peekNextArg()) != null) {
                if ("oneshot".equals(nextArg)) {
                    addOneShotToComposition(composition);
                } else if ("waveform".equals(nextArg)) {
                    addWaveformToComposition(composition);
                } else if ("prebaked".equals(nextArg)) {
                    addPrebakedToComposition(composition);
                } else if ("primitives".equals(nextArg)) {
                    addPrimitivesToComposition(composition);
                } else {
                    // nextArg is not an effect, finish reading.
                    break;
                }
            }

            return composition.compose();
        }

        private void addOneShotToComposition(VibrationEffect.Composition composition) {
            boolean hasAmplitude = false;
            int delay = 0;

            getNextArgRequired(); // consume "oneshot"
            String nextOption;
            while ((nextOption = getNextOption()) != null) {
                if ("-a".equals(nextOption)) {
                    hasAmplitude = true;
                } else if ("-w".equals(nextOption)) {
                    delay = Integer.parseInt(getNextArgRequired());
                }
            }

            long duration = Long.parseLong(getNextArgRequired());
            int amplitude = hasAmplitude ? Integer.parseInt(getNextArgRequired())
                    : VibrationEffect.DEFAULT_AMPLITUDE;
            composition.addOffDuration(Duration.ofMillis(delay));
            composition.addEffect(VibrationEffect.createOneShot(duration, amplitude));
        }

        private void addWaveformToComposition(VibrationEffect.Composition composition) {
            boolean hasAmplitudes = false;
            boolean hasFrequencies = false;
            boolean isContinuous = false;
            int repeat = -1;
            int delay = 0;

            getNextArgRequired(); // consume "waveform"
            String nextOption;
            while ((nextOption = getNextOption()) != null) {
                if ("-a".equals(nextOption)) {
                    hasAmplitudes = true;
                } else if ("-r".equals(nextOption)) {
                    repeat = Integer.parseInt(getNextArgRequired());
                } else if ("-w".equals(nextOption)) {
                    delay = Integer.parseInt(getNextArgRequired());
                } else if ("-f".equals(nextOption)) {
                    hasFrequencies = true;
                } else if ("-c".equals(nextOption)) {
                    isContinuous = true;
                }
            }
            List<Integer> durations = new ArrayList<>();
            List<Float> amplitudes = new ArrayList<>();
            List<Float> frequencies = new ArrayList<>();

            float nextAmplitude = 0;
            String nextArg;
            while ((nextArg = peekNextArg()) != null) {
                try {
                    durations.add(Integer.parseInt(nextArg));
                    getNextArgRequired(); // consume the duration
                } catch (NumberFormatException e) {
                    // nextArg is not a duration, finish reading.
                    break;
                }
                if (hasAmplitudes) {
                    amplitudes.add(
                            Float.parseFloat(getNextArgRequired()) / VibrationEffect.MAX_AMPLITUDE);
                } else {
                    amplitudes.add(nextAmplitude);
                    nextAmplitude = 1 - nextAmplitude;
                }
                if (hasFrequencies) {
                    frequencies.add(Float.parseFloat(getNextArgRequired()));
                }
            }

            // Add delay before the waveform.
            composition.addOffDuration(Duration.ofMillis(delay));

            VibrationEffect.WaveformBuilder waveform = VibrationEffect.startWaveform();
            for (int i = 0; i < durations.size(); i++) {
                Duration transitionDuration = isContinuous
                        ? Duration.ofMillis(durations.get(i))
                        : Duration.ZERO;
                Duration sustainDuration = isContinuous
                        ? Duration.ZERO
                        : Duration.ofMillis(durations.get(i));

                if (hasFrequencies) {
                    waveform.addTransition(transitionDuration, targetAmplitude(amplitudes.get(i)),
                            targetFrequency(frequencies.get(i)));
                } else {
                    waveform.addTransition(transitionDuration, targetAmplitude(amplitudes.get(i)));
                }
                if (!sustainDuration.isZero()) {
                    // Add sustain only takes positive durations. Skip this since we already
                    // did a transition to the desired values (even when duration is zero).
                    waveform.addSustain(sustainDuration);
                }

                if ((i > 0) && (i == repeat)) {
                    // Add segment that is not repeated to the composition and reset builder.
                    composition.addEffect(waveform.build());

                    if (hasFrequencies) {
                        waveform = VibrationEffect.startWaveform(targetAmplitude(amplitudes.get(i)),
                                targetFrequency(frequencies.get(i)));
                    } else {
                        waveform = VibrationEffect.startWaveform(
                                targetAmplitude(amplitudes.get(i)));
                    }
                }
            }
            if (repeat < 0) {
                composition.addEffect(waveform.build());
            } else {
                // The waveform was already split at the repeat index, just repeat what remains.
                composition.repeatEffectIndefinitely(waveform.build());
            }
        }

        private void addPrebakedToComposition(VibrationEffect.Composition composition) {
            boolean shouldFallback = false;
            int delay = 0;

            getNextArgRequired(); // consume "prebaked"
            String nextOption;
            while ((nextOption = getNextOption()) != null) {
                if ("-b".equals(nextOption)) {
                    shouldFallback = true;
                } else if ("-w".equals(nextOption)) {
                    delay = Integer.parseInt(getNextArgRequired());
                }
            }

            int effectId = Integer.parseInt(getNextArgRequired());
            composition.addOffDuration(Duration.ofMillis(delay));
            composition.addEffect(VibrationEffect.get(effectId, shouldFallback));
        }

        private void addPrimitivesToComposition(VibrationEffect.Composition composition) {
            getNextArgRequired(); // consume "primitives"
            String nextArg;
            while ((nextArg = peekNextArg()) != null) {
                int delay = 0;
                if ("-w".equals(nextArg)) {
                    getNextArgRequired(); // consume "-w"
                    delay = Integer.parseInt(getNextArgRequired());
                    nextArg = peekNextArg();
                }
                try {
                    composition.addPrimitive(Integer.parseInt(nextArg), /* scale= */ 1, delay);
                    getNextArgRequired(); // consume the primitive id
                } catch (NumberFormatException | NullPointerException e) {
                    // nextArg is not describing a primitive, leave it to be consumed by outer loops
                    break;
                }
            }
        }

        private VibrationAttributes createVibrationAttributes(CommonOptions commonOptions) {
            // This will bypass user settings, Do Not Disturb and other interruption policies.
            final int flags = commonOptions.force ? ATTRIBUTES_ALL_BYPASS_FLAGS : 0;
            return new VibrationAttributes.Builder()
                    .setFlags(flags)
                    // Used to allow vibrations when the adb shell process is running in background.
                    // This will apply the NOTIFICATION_VIBRATION_INTENSITY setting.
                    .setUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
                    .build();
        }

        private CombinedVibration parseXml(String xml) {
            try {
                ParsedVibration parsedVibration =
                        VibrationXmlParser.parseDocument(new StringReader(xml));
                if (parsedVibration == null) {
                    throw new IllegalArgumentException("Error parsing vibration XML " + xml);
                }
                VibratorInfo combinedVibratorInfo = getCombinedVibratorInfo();
                if (combinedVibratorInfo == null) {
                    throw new IllegalStateException(
                            "No combined vibrator info to parse vibration XML " + xml);
                }
                VibrationEffect effect = parsedVibration.resolve(combinedVibratorInfo);
                if (effect == null) {
                    throw new IllegalArgumentException(
                            "Parsed vibration cannot be resolved for vibration XML " + xml);
                }
                return CombinedVibration.createParallel(effect);
            } catch (IOException e) {
                throw new RuntimeException("Error parsing vibration XML " + xml, e);
            }
        }

        private void maybeWaitOnVibration(HalVibration vib, CommonOptions commonOptions) {
            if (vib != null && !commonOptions.background) {
                try {
                    // Waits for the client vibration to finish, but the VibrationThread may still
                    // do cleanup after this.
                    vib.waitForEnd();
                    // Wait for vibration clean up and possible ramp down before ending.
                    mVibrationThread.waitForThreadIdle(
                            mVibrationSettings.getRampDownDuration() + VIBRATION_END_TIMEOUT_MS);
                } catch (InterruptedException e) {
                }
            }
        }

        @Override
        public void onHelp() {
            try (PrintWriter pw = getOutPrintWriter();) {
                pw.println("Vibrator Manager commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("");
                pw.println("  list");
                pw.println("    Prints the id of device vibrators. This does not include any ");
                pw.println("    connected input device.");
                pw.println("  synced [options] <effect>...");
                pw.println("    Vibrates effect on all vibrators in sync.");
                pw.println("  combined [options] (-v <vibrator-id> <effect>...)...");
                pw.println("    Vibrates different effects on each vibrator in sync.");
                pw.println("  sequential [options] (-v <vibrator-id> <effect>...)...");
                pw.println("    Vibrates different effects on each vibrator in sequence.");
                pw.println("  xml [options] <xml>");
                pw.println("    Vibrates using combined vibration described in given XML string");
                pw.println("    on all vibrators in sync. The XML could be:");
                pw.println("        XML containing a single effect, or");
                pw.println("        A vibration select XML containing multiple effects.");
                pw.println("    Vibrates using combined vibration described in given XML string.");
                pw.println("    XML containing a single effect it runs on all vibrators in sync.");
                pw.println("  cancel");
                pw.println("    Cancels any active vibration");
                pw.println("  feedback [-f] [-d <description>] <constant>");
                pw.println("    Performs a haptic feedback with the given constant.");
                pw.println("    The force (-f) option enables the `always` configuration, which");
                pw.println("    plays the haptic irrespective of the vibration intensity settings");
                pw.println("");
                pw.println("Effect commands:");
                pw.println("  oneshot [-w delay] [-a] <duration> [<amplitude>]");
                pw.println("    Vibrates for duration milliseconds; ignored when device is on ");
                pw.println("    DND (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale amplitude.");
                pw.println("    If -w is provided, the effect will be played after the specified");
                pw.println("    wait time in milliseconds.");
                pw.println("    If -a is provided, the command accepts a second argument for ");
                pw.println("    amplitude, in a scale of 1-255.");
                pw.print("  waveform [-w delay] [-r index] [-a] [-f] [-c] ");
                pw.println("(<duration> [<amplitude>] [<frequency>])...");
                pw.println("    Vibrates for durations and amplitudes in list; ignored when ");
                pw.println("    device is on DND (Do Not Disturb) mode; touch feedback strength ");
                pw.println("    user setting will be used to scale amplitude.");
                pw.println("    If -w is provided, the effect will be played after the specified");
                pw.println("    wait time in milliseconds.");
                pw.println("    If -r is provided, the waveform loops back to the specified");
                pw.println("    index (e.g. 0 loops from the beginning)");
                pw.println("    If -a is provided, the command expects amplitude to follow each");
                pw.println("    duration; otherwise, it accepts durations only and alternates");
                pw.println("    off/on");
                pw.println("    If -f is provided, the command expects frequency to follow each");
                pw.println("    amplitude or duration; otherwise, it uses resonant frequency");
                pw.println("    If -c is provided, the waveform is continuous and will ramp");
                pw.println("    between values; otherwise each entry is a fixed step.");
                pw.println("    Duration is in milliseconds; amplitude is a scale of 1-255;");
                pw.println("    frequency is an absolute value in hertz;");
                pw.println("  prebaked [-w delay] [-b] <effect-id>");
                pw.println("    Vibrates with prebaked effect; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale amplitude.");
                pw.println("    If -w is provided, the effect will be played after the specified");
                pw.println("    wait time in milliseconds.");
                pw.println("    If -b is provided, the prebaked fallback effect will be played if");
                pw.println("    the device doesn't support the given effect-id.");
                pw.println("  primitives ([-w delay] <primitive-id>)...");
                pw.println("    Vibrates with a composed effect; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale primitive intensities.");
                pw.println("    If -w is provided, the next primitive will be played after the ");
                pw.println("    specified wait time in milliseconds.");
                pw.println("");
                pw.println("Common Options:");
                pw.println("  -f");
                pw.println("    Force. Ignore Do Not Disturb setting.");
                pw.println("  -B");
                pw.println("    Run in the background; without this option the shell cmd will");
                pw.println("    block until the vibration has completed.");
                pw.println("  -d <description>");
                pw.println("    Add description to the vibration.");
                pw.println("");
            }
        }
    }
}
