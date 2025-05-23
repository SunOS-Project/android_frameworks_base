/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;


import static com.android.systemui.Flags.truncatedStatusBarIconsFix;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.Dependency;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.user.ui.binder.StatusBarUserChipViewBinder;
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel;
import com.android.systemui.util.leak.RotationUtils;

import java.util.Objects;

import org.sun.systemui.statusbar.policy.Offset;
import org.sun.systemui.statusbar.ticker.MarqueeTicker;
import org.sun.systemui.statusbar.ticker.MarqueeTickerView;

public class PhoneStatusBarView extends FrameLayout {
    private static final String TAG = "PhoneStatusBarView";
    private final StatusBarContentInsetsProvider mContentInsetsProvider;
    private final StatusBarWindowController mStatusBarWindowController;

    private DarkReceiver mBattery;
    private Clock mClock;
    private Clock mClockCentre;
    private Clock mClockRight;
    private int mRotationOrientation = -1;
    @Nullable
    private View mCutoutSpace;
    @Nullable
    private View mTickerView;
    @Nullable
    private DisplayCutout mDisplayCutout;
    @Nullable
    private Rect mDisplaySize;
    private int mStatusBarHeight;
    @Nullable
    private ViewGroup mStatusBarContents = null;
    @Nullable
    private ViewGroup mStatusBarCenterClock = null;
    @Nullable
    private ViewGroup mTickerContainer = null;
    @Nullable
    private Gefingerpoken mTouchEventHandler;
    private int mDensity;
    private float mFontScale;

    /**
     * Draw this many pixels into the left/right side of the cutout to optimally use the space
     */
    private int mCutoutSideNudge = 0;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContentInsetsProvider = Dependency.get(StatusBarContentInsetsProvider.class);
        mStatusBarWindowController = Dependency.get(StatusBarWindowController.class);
    }

    void setTouchEventHandler(Gefingerpoken handler) {
        mTouchEventHandler = handler;
    }

    void init(StatusBarUserChipViewModel viewModel) {
        StatusBarUserSwitcherContainer container = findViewById(R.id.user_switcher_container);
        StatusBarUserChipViewBinder.bind(container, viewModel);
    }

    public void offsetStatusBar(Offset offset) {
        if (mStatusBarContents == null) {
            return;
        }
        mStatusBarContents.setTranslationX(offset.getX());
        mStatusBarContents.setTranslationY(offset.getY());
        if (mStatusBarCenterClock != null) {
            mStatusBarCenterClock.setTranslationY(offset.getY());
        }
        invalidate();
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mBattery = findViewById(R.id.battery);
        mClock = findViewById(R.id.clock);
        mClockCentre = findViewById(R.id.center_clock);
        mClockRight = findViewById(R.id.right_clock);
        mCutoutSpace = findViewById(R.id.cutout_space_view);
        mStatusBarContents = (ViewGroup) findViewById(R.id.status_bar_contents);
        mStatusBarCenterClock = (ViewGroup) findViewById(R.id.center_clock_layout);
        mTickerContainer = (ViewGroup) findViewById(R.id.ticker_container);

        updateResources();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Always have Battery meters in the status bar observe the dark/light modes.
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mBattery);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mClock);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mClockCentre);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mClockRight);
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            if (truncatedStatusBarIconsFix()) {
                updateWindowHeight();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mBattery);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mClock);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mClockCentre);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mClockRight);
        mDisplayCutout = null;
    }

    // Per b/300629388, we let the PhoneStatusBarView detect onConfigurationChanged to
    // updateResources, instead of letting the PhoneStatusBarViewController detect onConfigChanged
    // then notify PhoneStatusBarView.
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // May trigger cutout space layout-ing
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        if (truncatedStatusBarIconsFix()) {
            updateWindowHeight();
        }
    }

    void onDensityOrFontScaleChanged() {
        mClock.onDensityOrFontScaleChanged();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    /**
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateDisplayParameters() {
        boolean changed = false;
        int newRotation = RotationUtils.getExactRotation(mContext);
        if (newRotation != mRotationOrientation) {
            changed = true;
            mRotationOrientation = newRotation;
        }

        if (!Objects.equals(getRootWindowInsets().getDisplayCutout(), mDisplayCutout)) {
            changed = true;
            mDisplayCutout = getRootWindowInsets().getDisplayCutout();
        }

        Configuration newConfiguration = mContext.getResources().getConfiguration();
        final Rect newSize = newConfiguration.windowConfiguration.getMaxBounds();
        if (!Objects.equals(newSize, mDisplaySize)) {
            changed = true;
            mDisplaySize = newSize;
        }

        int density = newConfiguration.densityDpi;
        if (density != mDensity) {
            changed = true;
            mDensity = density;
        }
        float fontScale = newConfiguration.fontScale;
        if (fontScale != mFontScale) {
            changed = true;
            mFontScale = fontScale;
        }
        return changed;
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mTouchEventHandler == null) {
            Log.w(
                    TAG,
                    String.format(
                            "onTouch: No touch handler provided; eating gesture at (%d,%d)",
                            (int) event.getX(),
                            (int) event.getY()
                    )
            );
            return true;
        }
        return mTouchEventHandler.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        mTouchEventHandler.onInterceptTouchEvent(event);
        return super.onInterceptTouchEvent(event);
    }

    public void updateResources() {
        mCutoutSideNudge = getResources().getDimensionPixelSize(
                R.dimen.display_cutout_margin_consumption);

        updateStatusBarHeight();
    }

    private void updateStatusBarHeight() {
        final int waterfallTopInset =
                mDisplayCutout == null ? 0 : mDisplayCutout.getWaterfallInsets().top;
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        layoutParams.height = mStatusBarHeight - waterfallTopInset;
        updateSystemIconsContainerHeight();
        updatePaddings();
        setLayoutParams(layoutParams);
    }

    private void updateSystemIconsContainerHeight() {
        View systemIconsContainer = findViewById(R.id.system_icons);
        ViewGroup.LayoutParams layoutParams = systemIconsContainer.getLayoutParams();
        int newSystemIconsHeight =
                getResources().getDimensionPixelSize(R.dimen.status_bar_system_icons_height);
        if (layoutParams.height != newSystemIconsHeight) {
            layoutParams.height = newSystemIconsHeight;
            systemIconsContainer.setLayoutParams(layoutParams);
        }
    }

    private void updatePaddings() {
        int statusBarPaddingStart = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_start);

        mStatusBarContents.setPaddingRelative(
                statusBarPaddingStart,
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top),
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end),
                0);

        mTickerContainer.setPaddingRelative(
                statusBarPaddingStart,
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top),
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end),
                0);

        findViewById(R.id.notification_lights_out)
                .setPaddingRelative(0, statusBarPaddingStart, 0, 0);

        findViewById(R.id.system_icons).setPaddingRelative(
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_start),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_top),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_end),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_bottom)
        );
    }

    private void updateLayoutForCutout() {
        updateStatusBarHeight();
        updateCutoutLocation();
        updateSafeInsets();
        setDisplayToTicker(mDisplayCutout != null ? mDisplayCutout.getBoundingRectTop() : new Rect());
    }

    private void updateCutoutLocation() {
        // Not all layouts have a cutout (e.g., Car)
        if (mCutoutSpace == null) {
            return;
        }

        boolean hasCornerCutout = mContentInsetsProvider.currentRotationHasCornerCutout();
        if (mDisplayCutout == null || mDisplayCutout.isEmpty() || hasCornerCutout) {
            mCutoutSpace.setVisibility(View.GONE);
            return;
        }

        mCutoutSpace.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mCutoutSpace.getLayoutParams();

        Rect bounds = mDisplayCutout.getBoundingRectTop();

        bounds.left = bounds.left + mCutoutSideNudge;
        bounds.right = bounds.right - mCutoutSideNudge;
        lp.width = bounds.width();
        lp.height = bounds.height();
    }

    private void updateSafeInsets() {
        Insets insets = mContentInsetsProvider
                .getStatusBarContentInsetsForCurrentRotation();
        setPadding(
                insets.left,
                insets.top,
                insets.right,
                getPaddingBottom());

        // Apply negative paddings to centered area layout so that we'll actually be on the center.
        final int winRotation = getDisplay().getRotation();
        LayoutParams centeredAreaParams =
                (LayoutParams) findViewById(R.id.center_clock_layout).getLayoutParams();
        centeredAreaParams.leftMargin = winRotation == Surface.ROTATION_0 ? - insets.left : 0;
        centeredAreaParams.rightMargin = winRotation == Surface.ROTATION_0 ? - insets.right : 0;
    }

    private void updateWindowHeight() {
        mStatusBarWindowController.refreshStatusBarHeight();
    }

    public void setTickerView(View tickerView) {
        mTickerView = tickerView;
        setDisplayToTicker(mDisplayCutout != null ? mDisplayCutout.getBoundingRectTop() : new Rect());
    }

    private boolean isCenterDisplayCutout() {
        return mCutoutSpace.getVisibility() == View.VISIBLE;
    }

    public void setDisplayToTicker(Rect rect) {
        if (mTickerView == null || mDisplaySize == null || rect == null) {
            return;
        }
        final MarqueeTickerView tickerText = (MarqueeTickerView) mTickerView.findViewById(R.id.tickerText);
        final int tickerPaddingStart = getResources().getDimensionPixelSize(R.dimen.status_bar_padding_start);
        final int tickerPaddingEnd = getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end);
        final MarqueeTicker marqueeTicker = tickerText.getTicker();
        final int screenWidth = mDisplaySize.width();
        final int showTickerWidth = screenWidth - (tickerPaddingStart + tickerPaddingEnd);
        final int leftRect = screenWidth / 2 - tickerPaddingStart - rect.width() / 2;
        final int rightRect = leftRect + rect.width();
        marqueeTicker.setDisplayCutout(isCenterDisplayCutout(), leftRect, rightRect,
                showTickerWidth, mRotationOrientation != RotationUtils.ROTATION_LANDSCAPE,
                isCenterDisplayCutout(), 0);
    }
}
