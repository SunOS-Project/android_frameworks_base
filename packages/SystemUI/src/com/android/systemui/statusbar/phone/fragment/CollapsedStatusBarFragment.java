/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone.fragment;

import static com.android.systemui.statusbar.policy.Clock.CLOCK_ENABLED;
import static com.android.systemui.statusbar.policy.Clock.STYLE_CLOCK_CENTER;
import static com.android.systemui.statusbar.policy.Clock.STYLE_CLOCK_LEFT;
import static com.android.systemui.statusbar.policy.Clock.STYLE_CLOCK_RIGHT;

import static org.sun.provider.SettingsExt.System.STATUSBAR_CLOCK;
import static org.sun.provider.SettingsExt.System.STATUSBAR_CLOCK_STYLE;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Fragment;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.VisibleForTesting;
import androidx.core.animation.Animator;

import com.android.app.animation.Interpolators;
import com.android.app.animation.InterpolatorsAndroidX;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dumpable;
import com.android.systemui.Flags;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.OperatorNameView;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger.DisableState;
import com.android.systemui.statusbar.events.SystemStatusAnimationCallback;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerStatusBarViewBinder;
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarHideIconsForBouncerManager;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent.Startable;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallListener;
import com.android.systemui.statusbar.phone.ui.DarkIconManager;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.pipeline.shared.ui.binder.CollapsedStatusBarViewBinder;
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarVisibilityChangeListener;
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.CollapsedStatusBarViewModel;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.ClockCenter;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.statusbar.window.StatusBarWindowStateListener;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.CarrierConfigTracker.CarrierConfigChangedListener;
import com.android.systemui.util.CarrierConfigTracker.DefaultDataSubscriptionChangedListener;
import com.android.systemui.util.settings.SecureSettings;

import kotlin.Unit;

import kotlinx.coroutines.DisposableHandle;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import org.sun.systemui.statusbar.ticker.AdvertSwitcherView;
import org.sun.systemui.statusbar.ticker.AdvertTickerView;

/**
 * Contains the collapsed status bar and handles hiding/showing based on disable flags
 * and keyguard state. Also manages lifecycle to make sure the views it contains are being
 * updated by the StatusBarIconController and DarkIconManager while it is attached.
 */
@SuppressLint("ValidFragment")
public class CollapsedStatusBarFragment extends Fragment implements CommandQueue.Callbacks,
        StatusBarStateController.StateListener,
        SystemStatusAnimationCallback, Dumpable {

    public static final String TAG = "CollapsedStatusBarFragment";
    private static final String EXTRA_PANEL_STATE = "panel_state";
    public static final String STATUS_BAR_ICON_MANAGER_TAG = "status_bar_icon_manager";
    public static final int FADE_IN_DURATION = 320;
    public static final int FADE_OUT_DURATION = 160;
    public static final int FADE_IN_DELAY = 50;
    private static final int SOURCE_SYSTEM_EVENT_ANIMATOR = 1;
    private static final int SOURCE_OTHER = 2;
    private StatusBarFragmentComponent mStatusBarFragmentComponent;
    private PhoneStatusBarView mStatusBar;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardStateController mKeyguardStateController;
    private final PanelExpansionInteractor mPanelExpansionInteractor;
    private MultiSourceMinAlphaController mEndSideAlphaController;
    private LinearLayout mEndSideContent;
    private View mClockView;
    private View mOngoingActivityChip;
    private View mNotificationIconAreaInner;
    // Visibilities come in from external system callers via disable flags, but we also sometimes
    // modify the visibilities internally. We need to store both so that we don't accidentally
    // propagate our internally modified flags for too long.
    private StatusBarVisibilityModel mLastSystemVisibility =
            StatusBarVisibilityModel.createDefaultModel();
    private StatusBarVisibilityModel mLastModifiedVisibility =
            StatusBarVisibilityModel.createDefaultModel();
    private DarkIconManager mDarkIconManager;
    private final StatusBarFragmentComponent.Factory mStatusBarFragmentComponentFactory;
    private final CommandQueue mCommandQueue;
    private final CollapsedStatusBarFragmentLogger mCollapsedStatusBarFragmentLogger;
    private final OperatorNameViewController.Factory mOperatorNameViewControllerFactory;
    private final OngoingCallController mOngoingCallController;
    private final SystemStatusAnimationScheduler mAnimationScheduler;
    private final StatusBarLocationPublisher mLocationPublisher;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final ShadeExpansionStateManager mShadeExpansionStateManager;
    private final StatusBarIconController mStatusBarIconController;
    private final CarrierConfigTracker mCarrierConfigTracker;
    private final CollapsedStatusBarViewModel mCollapsedStatusBarViewModel;
    private final CollapsedStatusBarViewBinder mCollapsedStatusBarViewBinder;
    private final StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;
    private final DarkIconManager.Factory mDarkIconManagerFactory;
    private final SecureSettings mSecureSettings;
    private final Executor mMainExecutor;
    private final DumpManager mDumpManager;
    private final StatusBarWindowStateController mStatusBarWindowStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final NotificationIconContainerStatusBarViewBinder mNicViewBinder;
    private final DemoModeController mDemoModeController;

    private AdvertSwitcherView mAdvertSwitcherView;
    private AdvertTickerView mAdvertTickerView;
    private boolean mLastAdvertTickerViewShow;

    private ContentResolver mContentResolver;
    private SettingsObserver mSettingsObserver;

    private LinearLayout mCenterClockLayout;
    private View mRightClock;
    private int mClockStyle;
    private boolean mShowClock = true;

    private final class SettingsObserver extends ContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void register() {
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    STATUSBAR_CLOCK), false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    STATUSBAR_CLOCK_STYLE), false, this, UserHandle.USER_ALL);
        }

        void unregister() {
            mContentResolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings(true);
        }
    }

    private List<String> mBlockedIcons = new ArrayList<>();
    private Map<Startable, Startable.State> mStartableStates = new ArrayMap<>();

    private final OngoingCallListener mOngoingCallListener = new OngoingCallListener() {
        @Override
        public void onOngoingCallStateChanged(boolean animate) {
            updateStatusBarVisibilities(animate);
        }
    };
    private OperatorNameViewController mOperatorNameViewController;
    private StatusBarSystemEventDefaultAnimator mSystemEventAnimator;

    private final CarrierConfigChangedListener mCarrierConfigCallback =
            new CarrierConfigChangedListener() {
                @Override
                public void onCarrierConfigChanged() {
                    if (mOperatorNameViewController == null) {
                        initOperatorName();
                    } else {
                        // Already initialized, KeyguardUpdateMonitorCallback will handle the update
                    }
                }
            };

    private final DefaultDataSubscriptionChangedListener mDefaultDataListener =
            new DefaultDataSubscriptionChangedListener() {
                @Override
                public void onDefaultSubscriptionChanged(int subId) {
                    if (mOperatorNameViewController == null) {
                        initOperatorName();
                    }
                }
            };

    /**
     * Whether we've launched the secure camera over the lockscreen, but haven't yet received a
     * status bar window state change afterward.
     *
     * We wait for this state change (which will tell us whether to show/hide the status bar icons)
     * so that there is no flickering/jump cutting during the camera launch.
     */
    private boolean mWaitingForWindowStateChangeAfterCameraLaunch = false;

    /**
     * True when a transition from lockscreen to dream has started, but haven't yet received a
     * status bar window state change afterward.
     *
     * Similar to [mWaitingForWindowStateChangeAfterCameraLaunch].
     */
    private boolean mTransitionFromLockscreenToDreamStarted = false;

    /**
     * True if there's an active ongoing activity that should be showing a chip and false otherwise.
     */
    private boolean mHasOngoingActivity;

    /**
     * Listener that updates {@link #mWaitingForWindowStateChangeAfterCameraLaunch} when it receives
     * a new status bar window state.
     */
    private final StatusBarWindowStateListener mStatusBarWindowStateListener = state -> {
        mWaitingForWindowStateChangeAfterCameraLaunch = false;
        mTransitionFromLockscreenToDreamStarted = false;
    };
    private DisposableHandle mNicBindingDisposable;

    private boolean mAnimationsEnabled = true;

    @Inject
    public CollapsedStatusBarFragment(
            StatusBarFragmentComponent.Factory statusBarFragmentComponentFactory,
            OngoingCallController ongoingCallController,
            SystemStatusAnimationScheduler animationScheduler,
            StatusBarLocationPublisher locationPublisher,
            NotificationIconAreaController notificationIconAreaController,
            ShadeExpansionStateManager shadeExpansionStateManager,
            StatusBarIconController statusBarIconController,
            DarkIconManager.Factory darkIconManagerFactory,
            CollapsedStatusBarViewModel collapsedStatusBarViewModel,
            CollapsedStatusBarViewBinder collapsedStatusBarViewBinder,
            StatusBarHideIconsForBouncerManager statusBarHideIconsForBouncerManager,
            KeyguardStateController keyguardStateController,
            PanelExpansionInteractor panelExpansionInteractor,
            StatusBarStateController statusBarStateController,
            NotificationIconContainerStatusBarViewBinder nicViewBinder,
            CommandQueue commandQueue,
            CarrierConfigTracker carrierConfigTracker,
            CollapsedStatusBarFragmentLogger collapsedStatusBarFragmentLogger,
            OperatorNameViewController.Factory operatorNameViewControllerFactory,
            SecureSettings secureSettings,
            @Main Executor mainExecutor,
            DumpManager dumpManager,
            StatusBarWindowStateController statusBarWindowStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DemoModeController demoModeController) {
        mStatusBarFragmentComponentFactory = statusBarFragmentComponentFactory;
        mOngoingCallController = ongoingCallController;
        mAnimationScheduler = animationScheduler;
        mLocationPublisher = locationPublisher;
        mNotificationIconAreaController = notificationIconAreaController;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mStatusBarIconController = statusBarIconController;
        mCollapsedStatusBarViewModel = collapsedStatusBarViewModel;
        mCollapsedStatusBarViewBinder = collapsedStatusBarViewBinder;
        mStatusBarHideIconsForBouncerManager = statusBarHideIconsForBouncerManager;
        mDarkIconManagerFactory = darkIconManagerFactory;
        mKeyguardStateController = keyguardStateController;
        mPanelExpansionInteractor = panelExpansionInteractor;
        mStatusBarStateController = statusBarStateController;
        mNicViewBinder = nicViewBinder;
        mCommandQueue = commandQueue;
        mCarrierConfigTracker = carrierConfigTracker;
        mCollapsedStatusBarFragmentLogger = collapsedStatusBarFragmentLogger;
        mOperatorNameViewControllerFactory = operatorNameViewControllerFactory;
        mSecureSettings = secureSettings;
        mMainExecutor = mainExecutor;
        mDumpManager = dumpManager;
        mStatusBarWindowStateController = statusBarWindowStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDemoModeController = demoModeController;
    }

    private final DemoMode mDemoModeCallback = new DemoMode() {
        @Override
        public List<String> demoCommands() {
            return List.of(DemoMode.COMMAND_NOTIFICATIONS);
        }

        @Override
        public void dispatchDemoCommand(String command, Bundle args) {
            if (mNotificationIconAreaInner == null) return;
            String visible = args.getString("visible");
            if ("false".equals(visible)) {
                mNotificationIconAreaInner.setVisibility(View.INVISIBLE);
            } else {
                mNotificationIconAreaInner.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onDemoModeFinished() {
            if (mNotificationIconAreaInner == null) return;
            mNotificationIconAreaInner.setVisibility(View.VISIBLE);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStatusBarWindowStateController.addListener(mStatusBarWindowStateListener);
        if (NotificationIconContainerRefactor.isEnabled()) {
            mDemoModeController.addCallback(mDemoModeCallback);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStatusBarWindowStateController.removeListener(mStatusBarWindowStateListener);
        if (NotificationIconContainerRefactor.isEnabled()) {
            mDemoModeController.removeCallback(mDemoModeCallback);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.status_bar, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mDumpManager.registerDumpable(getClass().getSimpleName(), this);
        mStatusBarFragmentComponent = mStatusBarFragmentComponentFactory.create(
                (PhoneStatusBarView) getView());
        mStatusBarFragmentComponent.init();
        mStartableStates.clear();
        for (Startable startable : mStatusBarFragmentComponent.getStartables()) {
            mStartableStates.put(startable, Startable.State.STARTING);
            startable.start();
            mStartableStates.put(startable, Startable.State.STARTED);
        }

        mStatusBar = (PhoneStatusBarView) view;
        View contents = mStatusBar.findViewById(R.id.status_bar_contents);
        contents.addOnLayoutChangeListener(mStatusBarLayoutListener);
        updateStatusBarLocation(contents.getLeft(), contents.getRight());
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_PANEL_STATE)) {
            mStatusBar.restoreHierarchyState(
                    savedInstanceState.getSparseParcelableArray(EXTRA_PANEL_STATE));
        }
        mDarkIconManager = mDarkIconManagerFactory.create(
                view.findViewById(R.id.statusIcons), StatusBarLocation.HOME);
        mDarkIconManager.setShouldLog(true);
        updateBlockedIcons();
        mStatusBarIconController.addIconGroup(mDarkIconManager);
        mEndSideContent = mStatusBar.findViewById(R.id.status_bar_end_side_content);
        mEndSideAlphaController = new MultiSourceMinAlphaController(mEndSideContent);
        mClockView = mStatusBar.findViewById(R.id.clock);
        mOngoingActivityChip = mStatusBar.findViewById(R.id.ongoing_activity_chip);
        mCenterClockLayout = (LinearLayout) mStatusBar.findViewById(R.id.center_clock_layout);
        mRightClock = mStatusBar.findViewById(R.id.right_clock);
        showEndSideContent(false);
        animateHide(mClockView, false, false);
        initOperatorName();
        initNotificationIconArea();
        mSystemEventAnimator = getSystemEventAnimator();
        mCarrierConfigTracker.addCallback(mCarrierConfigCallback);
        mCarrierConfigTracker.addDefaultDataSubscriptionChangedListener(mDefaultDataListener);

        mAdvertTickerView = (AdvertTickerView) mStatusBar.findViewById(R.id.ticker_ext);
        mAdvertSwitcherView = (AdvertSwitcherView) mStatusBar.findViewById(R.id.status_bar_switcher);
        mAdvertSwitcherView.setCallBack(show -> updateStatusBarVisibilities(/* animate= */ false));
        mAdvertSwitcherView.setCenterClockView((ClockCenter) mStatusBar.findViewById(R.id.center_clock));

        mContentResolver = getContext().getContentResolver();
        mSettingsObserver = new SettingsObserver(new Handler());
        mSettingsObserver.register();
        updateSettings(false);

        mCollapsedStatusBarViewBinder.bind(
                mStatusBar, mCollapsedStatusBarViewModel, mStatusBarVisibilityChangeListener);
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        mWaitingForWindowStateChangeAfterCameraLaunch = true;
    }

    @VisibleForTesting
    void updateBlockedIcons() {
        mBlockedIcons.clear();

        // Reload the blocklist from res
        List<String> blockList = Arrays.asList(getResources().getStringArray(
                R.array.config_collapsed_statusbar_icon_blocklist));
        for (int i = 0; i < blockList.size(); i++) {
            mBlockedIcons.add(blockList.get(i));
        }

        mMainExecutor.execute(() -> mDarkIconManager.setBlockList(mBlockedIcons));
    }

    @VisibleForTesting
    List<String> getBlockedIcons() {
        return mBlockedIcons;
    }


    @VisibleForTesting
    void enableAnimationsForTesting() {
        mAnimationsEnabled = true;
    }

    @VisibleForTesting
    void disableAnimationsForTesting() {
        mAnimationsEnabled = false;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        SparseArray<Parcelable> states = new SparseArray<>();
        mStatusBar.saveHierarchyState(states);
        outState.putSparseParcelableArray(EXTRA_PANEL_STATE, states);
    }

    @Override
    public void onResume() {
        super.onResume();
        mCommandQueue.addCallback(this);
        mStatusBarStateController.addCallback(this);
        initOngoingCallChip();
        mAnimationScheduler.addCallback(this);

        mSecureSettings.registerContentObserverForUserSync(
                Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON,
                false,
                mVolumeSettingObserver,
                UserHandle.USER_ALL);
    }

    @Override
    public void onPause() {
        super.onPause();
        mSettingsObserver.unregister();
        mCommandQueue.removeCallback(this);
        mStatusBarStateController.removeCallback(this);
        mOngoingCallController.removeCallback(mOngoingCallListener);
        mAnimationScheduler.removeCallback(this);
        mSecureSettings.unregisterContentObserverSync(mVolumeSettingObserver);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mStatusBarIconController.removeIconGroup(mDarkIconManager);
        mCarrierConfigTracker.removeCallback(mCarrierConfigCallback);
        mCarrierConfigTracker.removeDataSubscriptionChangedListener(mDefaultDataListener);

        for (Startable startable : mStatusBarFragmentComponent.getStartables()) {
            mStartableStates.put(startable, Startable.State.STOPPING);
            startable.stop();
            mStartableStates.put(startable, Startable.State.STOPPED);
        }
        mDumpManager.unregisterDumpable(getClass().getSimpleName());
        if (NotificationIconContainerRefactor.isEnabled()) {
            if (mNicBindingDisposable != null) {
                mNicBindingDisposable.dispose();
                mNicBindingDisposable = null;
            }
        }
    }

    /** Initializes views related to the notification icon area. */
    public void initNotificationIconArea() {
        Trace.beginSection("CollapsedStatusBarFragment#initNotifIconArea");
        ViewGroup notificationIconArea = mStatusBar.requireViewById(R.id.notification_icon_area);
        if (NotificationIconContainerRefactor.isEnabled()) {
            LayoutInflater.from(getContext())
                    .inflate(R.layout.notification_icon_area, notificationIconArea, true);
            NotificationIconContainer notificationIcons =
                    notificationIconArea.requireViewById(R.id.notificationIcons);
            mNotificationIconAreaInner = notificationIcons;
            mNicBindingDisposable = mNicViewBinder.bindWhileAttached(notificationIcons);
        } else {
            mNotificationIconAreaInner =
                    mNotificationIconAreaController.getNotificationInnerAreaView();
            if (mNotificationIconAreaInner.getParent() != null) {
                ((ViewGroup) mNotificationIconAreaInner.getParent())
                        .removeView(mNotificationIconAreaInner);
            }
            notificationIconArea.addView(mNotificationIconAreaInner);
        }

        updateNotificationIconAreaAndOngoingActivityChip(/* animate= */ false);
        Trace.endSection();
    }

    /**
     * Returns the dagger component for this fragment.
     *
     * TODO(b/205609837): Eventually, the dagger component should encapsulate all status bar
     *   fragment functionality and we won't need to expose it here anymore.
     */
    @Nullable
    public StatusBarFragmentComponent getStatusBarFragmentComponent() {
        return mStatusBarFragmentComponent;
    }

    private StatusBarVisibilityChangeListener mStatusBarVisibilityChangeListener =
            new StatusBarVisibilityChangeListener() {
                @Override
                public void onStatusBarVisibilityMaybeChanged() {
                    updateStatusBarVisibilities(/* animate= */ true);
                }

                @Override
                public void onTransitionFromLockscreenToDreamStarted() {
                    mTransitionFromLockscreenToDreamStarted = true;
                }

                @Override
                public void onOngoingActivityStatusChanged(boolean hasOngoingActivity) {
                    mHasOngoingActivity = hasOngoingActivity;
                    updateStatusBarVisibilities(/* animate= */ true);
                }
    };

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getContext().getDisplayId()) {
            return;
        }
        mCollapsedStatusBarFragmentLogger
                .logDisableFlagChange(new DisableState(state1, state2));
        mLastSystemVisibility =
                StatusBarVisibilityModel.createModelFromFlags(state1, state2);
        updateStatusBarVisibilities(animate);
    }

    private void updateStatusBarVisibilities(boolean animate) {
        StatusBarVisibilityModel previousModel = mLastModifiedVisibility;
        StatusBarVisibilityModel newModel = calculateInternalModel(mLastSystemVisibility);
        mCollapsedStatusBarFragmentLogger.logVisibilityModel(newModel);
        mLastModifiedVisibility = newModel;

        if (newModel.getShowSystemInfo() != previousModel.getShowSystemInfo()) {
            if (newModel.getShowSystemInfo()) {
                showEndSideContent(animate);
                showOperatorName(animate);
            } else {
                hideEndSideContent(animate);
                hideOperatorName(animate);
            }
        }

        // The ongoing activity chip and notification icon visibilities are intertwined, so update
        // both if either change.
        boolean notifsChanged =
                newModel.getShowNotificationIcons() != previousModel.getShowNotificationIcons();
        boolean ongoingActivityChanged =
                newModel.getShowOngoingActivityChip() != previousModel.getShowOngoingActivityChip();
        boolean advertTickerViewShow = getAdvertSwitcherShow();
        if (notifsChanged || ongoingActivityChanged || advertTickerViewShow != mLastAdvertTickerViewShow) {
            updateNotificationIconAreaAndOngoingActivityChip(animate);
        }

        mLastAdvertTickerViewShow = advertTickerViewShow;
    }

    private StatusBarVisibilityModel calculateInternalModel(
            StatusBarVisibilityModel externalModel) {
        // TODO(b/328393714) use HeadsUpNotificationInteractor.showHeadsUpStatusBar instead.
        boolean headsUpVisible =
                mStatusBarFragmentComponent.getHeadsUpAppearanceController().shouldBeVisible();

        if (!mKeyguardStateController.isLaunchTransitionFadingAway()
                && !mKeyguardStateController.isKeyguardFadingAway()
                && shouldHideStatusBar()
                && !(mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                        && headsUpVisible)) {
            // Hide everything
            return new StatusBarVisibilityModel(
                    /* showClock= */ false,
                    /* showNotificationIcons= */ false,
                    /* showOngoingActivityChip= */ false,
                    /* showSystemInfo= */ false);
        }

        boolean showClock = externalModel.getShowClock() && !headsUpVisible;

        boolean showOngoingActivityChip;
        if (Flags.statusBarScreenSharingChips()) {
            // If this flag is on, the ongoing activity status comes from
            // CollapsedStatusBarViewBinder, which updates the mHasOngoingActivity variable.
            showOngoingActivityChip = mHasOngoingActivity;
        } else {
            // If this flag is off, the only ongoing activity is the ongoing call, and we pull it
            // from the controller directly.
            showOngoingActivityChip = mOngoingCallController.hasOngoingCall();
        }

        return new StatusBarVisibilityModel(
                showClock,
                externalModel.getShowNotificationIcons(),
                showOngoingActivityChip && !headsUpVisible,
                externalModel.getShowSystemInfo());
    }

    /**
     * Updates the visibility of the notification icon area and ongoing activity chip based on
     * mLastModifiedVisibility.
     */
    private void updateNotificationIconAreaAndOngoingActivityChip(boolean animate) {
        StatusBarVisibilityModel visibilityModel = mLastModifiedVisibility;
        boolean disableNotifications = !visibilityModel.getShowNotificationIcons();
        boolean hasOngoingActivity = visibilityModel.getShowOngoingActivityChip();
        boolean advertTickerShow = getAdvertSwitcherShow();

        // Hide notifications if the disable flag is set or we have an ongoing activity.
        if (disableNotifications || hasOngoingActivity) {
            hideNotificationIconArea(animate && !hasOngoingActivity);
            hideLyricsTicker(animate);
            animateHide(mClockView, animate, false);
        } else if (advertTickerShow) {
            showLyricsTicker(animate);
        } else {
            showNotificationIconArea(animate);
            updateClockStyle(animate);
        }

        // Show the ongoing activity chip only if there is an ongoing activity *and* notification
        // icons are allowed. (The ongoing activity chip occupies the same area as the notification,
        // icons so if the icons are disabled then the activity chip should be, too.)
        boolean showOngoingActivityChip = hasOngoingActivity && !disableNotifications;
        if (showOngoingActivityChip) {
            showOngoingActivityChip(animate);
        } else {
            hideOngoingActivityChip(animate);
        }
        if (!Flags.statusBarScreenSharingChips()) {
            mOngoingCallController.notifyChipVisibilityChanged(showOngoingActivityChip);
        }
    }

    private boolean shouldHideStatusBar() {
        if (!mShadeExpansionStateManager.isClosed()
                && mPanelExpansionInteractor.shouldHideStatusBarIconsWhenExpanded()) {
            return true;
        }

        // When launching the camera over the lockscreen, the icons become visible momentarily
        // before animating out, since we're not yet aware that the launching camera activity is
        // fullscreen. Even once the activity finishes launching, it takes a short time before WM
        // decides that the top app wants to hide the icons and tells us to hide them. To ensure
        // that this high-visibility animation is smooth, keep the icons hidden during a camera
        // launch until we receive a window state change which indicates that the activity is done
        // launching and WM has decided to show/hide the icons. For extra safety (to ensure the
        // icons don't remain hidden somehow) we double check that the camera is still showing, the
        // status bar window isn't hidden, and we're still occluded as well, though these checks
        // are typically unnecessary.
        //
        // TODO(b/273314977): Can this be deleted now that we have the
        //   [isTransitioningFromLockscreenToOccluded] check below?
        final boolean hideIconsForSecureCamera =
                (mWaitingForWindowStateChangeAfterCameraLaunch ||
                        !mStatusBarWindowStateController.windowIsShowing()) &&
                        mKeyguardUpdateMonitor.isSecureCameraLaunchedOverKeyguard() &&
                        mKeyguardStateController.isOccluded();

        if (hideIconsForSecureCamera) {
            return true;
        }

        // Similar to [hideIconsForSecureCamera]: When dream is launched over lockscreen, the icons
        // are momentarily visible because the dream animation has finished, but SysUI has not been
        // informed that the dream is full-screen. For extra safety, we double-check that we're
        // still dreaming.
        final boolean hideIconsForDream =
                mTransitionFromLockscreenToDreamStarted
                        && mKeyguardUpdateMonitor.isDreaming()
                        && mKeyguardStateController.isOccluded();
        if (hideIconsForDream) {
            return true;
        }

        // While the status bar is transitioning from lockscreen to an occluded, we don't yet know
        // if the occluding activity is fullscreen or not. If it *is* fullscreen, we don't want to
        // briefly show the status bar just to immediately hide it again. So, we wait for the
        // transition to occluding to finish before allowing us to potentially show the status bar
        // again. (This status bar is always hidden on keyguard, so it's safe to continue hiding it
        // during this transition.) See b/273314977.
        if (mCollapsedStatusBarViewModel.isTransitioningFromLockscreenToOccluded().getValue()) {
            return true;
        }

        return mStatusBarHideIconsForBouncerManager.getShouldHideStatusBarIconsForBouncer();
    }

    private void hideEndSideContent(boolean animate) {
        animateHide(mCenterClockLayout, animate, true);
        if (mClockStyle == STYLE_CLOCK_RIGHT) {
            animateHide(mRightClock, animate, true);
        }
        if (!animate) {
            mEndSideAlphaController.setAlpha(/*alpha*/ 0f, SOURCE_OTHER);
        } else {
            mEndSideAlphaController.animateToAlpha(/*alpha*/ 0f, SOURCE_OTHER, FADE_OUT_DURATION,
                    InterpolatorsAndroidX.ALPHA_OUT, /*startDelay*/ 0);
        }
    }

    private void showEndSideContent(boolean animate) {
        animateShow(mCenterClockLayout, animate);
        if (mClockStyle == STYLE_CLOCK_RIGHT) {
            animateShow(mRightClock, animate);
        }
        if (!animate) {
            mEndSideAlphaController.setAlpha(1f, SOURCE_OTHER);
            return;
        }
        if (mKeyguardStateController.isKeyguardFadingAway()) {
            mEndSideAlphaController.animateToAlpha(/*alpha*/ 1f, SOURCE_OTHER,
                    mKeyguardStateController.getKeyguardFadingAwayDuration(),
                    InterpolatorsAndroidX.LINEAR_OUT_SLOW_IN,
                    mKeyguardStateController.getKeyguardFadingAwayDelay());
        } else {
            mEndSideAlphaController.animateToAlpha(/*alpha*/ 1f, SOURCE_OTHER, FADE_IN_DURATION,
                    InterpolatorsAndroidX.ALPHA_IN, FADE_IN_DELAY);
        }
    }

    /** Hides the ongoing activity chip. */
    private void hideOngoingActivityChip(boolean animate) {
        animateHiddenState(mOngoingActivityChip, View.GONE, animate);
    }

    /**
     * Displays the ongoing activity chip.
     *
     * If Flags.statusBarScreenSharingChips is disabled, this chip will only ever contain the
     * ongoing call information, If that flag is enabled, it will support different kinds of ongoing
     * activities. See b/332662551.
     */
    private void showOngoingActivityChip(boolean animate) {
        animateShow(mOngoingActivityChip, animate);
    }

    public void hideNotificationIconArea(boolean animate) {
        animateHide(mNotificationIconAreaInner, animate);
        animateHide(mCenterClockLayout, animate, true);
    }

    public void showNotificationIconArea(boolean animate) {
        animateShow(mNotificationIconAreaInner, animate);
        animateShow(mCenterClockLayout, animate);
    }

    public void hideOperatorName(boolean animate) {
        if (mOperatorNameViewController != null) {
            animateHide(mOperatorNameViewController.getView(), animate);
        }
    }

    public void showOperatorName(boolean animate) {
        if (mOperatorNameViewController != null) {
            animateShow(mOperatorNameViewController.getView(), animate);
        }
    }

    /**
     * Animate a view to INVISIBLE or GONE
     */
    private void animateHiddenState(final View v, int state, boolean animate) {
        v.animate().cancel();
        if (!animate || !mAnimationsEnabled) {
            v.setAlpha(0f);
            v.setVisibility(state);
            return;
        }

        v.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_DURATION)
                .setStartDelay(0)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(() -> v.setVisibility(state));
    }

    /**
     * Hides a view.
     */
    private void animateHide(final View v, boolean animate) {
        animateHiddenState(v, View.INVISIBLE, animate);
    }

    /**
     * Hides a view.
     */
    private void animateHide(final View v, boolean animate, boolean invisible) {
        animateHiddenState(v, invisible ? View.INVISIBLE : View.GONE, animate);
    }

    /**
     * Shows a view, and synchronizes the animation with Keyguard exit animations, if applicable.
     */
    private void animateShow(View v, boolean animate) {
        if (v instanceof Clock && !((Clock) v).isClockVisible()) {
            return;
        }
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!animate || !mAnimationsEnabled) {
            v.setAlpha(1f);
            return;
        }
        v.animate()
                .alpha(1f)
                .setDuration(FADE_IN_DURATION)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setStartDelay(FADE_IN_DELAY)

                // We need to clean up any pending end action from animateHide if we call
                // both hide and show in the same frame before the animation actually gets started.
                // cancel() doesn't really remove the end action.
                .withEndAction(null);

        // Synchronize the motion with the Keyguard fading if necessary.
        if (mKeyguardStateController.isKeyguardFadingAway()) {
            v.animate()
                    .setDuration(mKeyguardStateController.getKeyguardFadingAwayDuration())
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .setStartDelay(mKeyguardStateController.getKeyguardFadingAwayDelay())
                    .start();
        }
    }

    private void initOperatorName() {
        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (mCarrierConfigTracker.getShowOperatorNameInStatusBarConfig(subId)) {
            View view = mStatusBar.findViewById(R.id.operator_name);
            mOperatorNameViewController =
                    mOperatorNameViewControllerFactory.create((OperatorNameView) view);
            mOperatorNameViewController.init();
            // This view should not be visible on lock-screen
            if (mKeyguardStateController.isShowing()) {
                hideOperatorName(false);
            }
        }
    }

    private void initOngoingCallChip() {
        mOngoingCallController.addCallback(mOngoingCallListener);
        mOngoingCallController.setChipView(mOngoingActivityChip);
    }

    @Override
    public void onStateChanged(int newState) { }

    @Override
    public void onDozingChanged(boolean isDozing) {
        updateStatusBarVisibilities(/* animate= */ false);
    }

    @Nullable
    @Override
    public Animator onSystemEventAnimationBegin() {
        return mSystemEventAnimator.onSystemEventAnimationBegin();
    }

    @Nullable
    @Override
    public Animator onSystemEventAnimationFinish(boolean hasPersistentDot) {
        return mSystemEventAnimator.onSystemEventAnimationFinish(hasPersistentDot);
    }

    private StatusBarSystemEventDefaultAnimator getSystemEventAnimator() {
        return new StatusBarSystemEventDefaultAnimator(getResources(), (alpha) -> {
            mEndSideAlphaController.setAlpha(alpha, SOURCE_SYSTEM_EVENT_ANIMATOR);
            return Unit.INSTANCE;
        }, (translationX) -> {
            mEndSideContent.setTranslationX(translationX);
            return Unit.INSTANCE;
        }, /*isAnimationRunning*/ false);
    }

    private void updateStatusBarLocation(int left, int right) {
        int leftMargin = left - mStatusBar.getLeft();
        int rightMargin = mStatusBar.getRight() - right;

        mLocationPublisher.updateStatusBarMargin(leftMargin, rightMargin);
    }

    private void hideLyricsTicker(boolean animate) {
        if (mAdvertTickerView != null) {
            animateHiddenState(mAdvertTickerView, View.INVISIBLE, animate);
        }
    }

    private void showLyricsTicker(boolean animate) {
        if (getAdvertSwitcherShow()) {
            animateShow(mAdvertTickerView, animate);
        }
    }

    private boolean getAdvertSwitcherShow() {
        if (mAdvertSwitcherView == null) {
            return false;
        }
        return mAdvertSwitcherView.isShow();
    }

    private void updateSettings(boolean animate) {
        mShowClock = Settings.System.getIntForUser(mContentResolver,
                STATUSBAR_CLOCK, CLOCK_ENABLED,
                UserHandle.USER_CURRENT) == CLOCK_ENABLED;
        if (!mShowClock) {
            mClockStyle = STYLE_CLOCK_CENTER;
        } else {
            int style = Settings.System.getIntForUser(mContentResolver,
                    STATUSBAR_CLOCK_STYLE, STYLE_CLOCK_LEFT,
                    UserHandle.USER_CURRENT);
            if (style == STYLE_CLOCK_CENTER) {
                final boolean hasCenteredCutout = getContext().getResources().getBoolean(
                        com.android.internal.R.bool.config_hasCenteredCutout);
                if (hasCenteredCutout) {
                    style = STYLE_CLOCK_LEFT;
                }
            }
            mClockStyle = style;
        }
        updateClockStyle(animate);
    }

    private void updateClockStyle(boolean animate) {
        if (mClockStyle == STYLE_CLOCK_CENTER || mClockStyle == STYLE_CLOCK_RIGHT) {
            animateHide(mClockView, animate, false);
        } else if (((Clock) mClockView).isClockVisible()) {
            animateShow(mClockView, animate);
        }
    }

    private final ContentObserver mVolumeSettingObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            updateBlockedIcons();
        }
    };

    // Listen for view end changes of PhoneStatusBarView and publish that to the privacy dot
    private View.OnLayoutChangeListener mStatusBarLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (left != oldLeft || right != oldRight) {
                    updateStatusBarLocation(left, right);
                }
            };

    @Override
    public void dump(PrintWriter printWriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, /* singleIndent= */"  ");
        pw.println("mHasOngoingActivity=" + mHasOngoingActivity);
        pw.println("mAnimationsEnabled=" + mAnimationsEnabled);
        StatusBarFragmentComponent component = mStatusBarFragmentComponent;
        if (component == null) {
            pw.println("StatusBarFragmentComponent is null");
        } else {
            Set<Startable> startables = component.getStartables();
            pw.println("Startables: " + startables.size());
            pw.increaseIndent();
            for (Startable startable : startables) {
                Startable.State startableState = mStartableStates.getOrDefault(startable,
                        Startable.State.NONE);
                pw.println(startable + ", state: " + startableState);
            }
            pw.decreaseIndent();
        }
    }
}
