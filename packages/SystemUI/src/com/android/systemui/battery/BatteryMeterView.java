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
package com.android.systemui.battery;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import static org.sun.provider.SettingsExt.System.STATUS_BAR_BATTERY_STYLE;
import static org.sun.provider.SettingsExt.System.STATUS_BAR_SHOW_BATTERY_PERCENT;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StyleRes;
import androidx.annotation.VisibleForTesting;

import com.android.app.animation.Interpolators;
import com.android.settingslib.graph.CircleBatteryDrawable;
import com.android.systemui.DualToneHandler;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BatteryController;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.text.NumberFormat;
import java.util.ArrayList;

public class BatteryMeterView extends LinearLayout implements DarkReceiver {

    public static final int BATTERY_PERCENT_HIDDEN = 0;
    public static final int BATTERY_PERCENT_INSIDE = 1;
    public static final int BATTERY_PERCENT_OUTSIDE = 2;

    public static final int BATTERY_STYLE_PORTRAIT = 0;
    public static final int BATTERY_STYLE_CIRCLE = 1;
    public static final int BATTERY_STYLE_TEXT = 2;
    public static final int BATTERY_STYLE_HIDDEN = 3;

    @Retention(SOURCE)
    @IntDef({MODE_DEFAULT, MODE_ON, MODE_OFF, MODE_ESTIMATE})
    public @interface BatteryPercentMode {}
    public static final int MODE_DEFAULT = 0;
    public static final int MODE_ON = 1;
    public static final int MODE_OFF = 2;
    public static final int MODE_ESTIMATE = 3;

    private final AccessorizedBatteryDrawable mDrawable;
    private final CircleBatteryDrawable mCircleDrawable;
    private ImageView mBatteryIconView;
    private TextView mBatteryPercentView;

    private int mBatteryStyle;
    private int mBatteryShowPercent;

    private final @StyleRes int mPercentageStyleId;
    private int mTextColor;
    private int mLevel;
    private int mShowPercentMode = MODE_DEFAULT;
    private String mEstimateText = null;
    private boolean mPluggedIn;
    private boolean mPowerSaveEnabled;
    private boolean mIsBatteryDefender;
    private boolean mIsIncompatibleCharging;
    private boolean mDisplayShieldEnabled;
    // Error state where we know nothing about the current battery state
    private boolean mBatteryStateUnknown;
    // Lazily-loaded since this is expected to be a rare-if-ever state
    private Drawable mUnknownStateDrawable;

    private DualToneHandler mDualToneHandler;
    private boolean mIsStaticColor = false;

    private BatteryEstimateFetcher mBatteryEstimateFetcher;

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                context.getColor(com.android.settingslib.R.color.meter_background_color));
        mPercentageStyleId = atts.getResourceId(R.styleable.BatteryMeterView_textAppearance, 0);

        mDrawable = new AccessorizedBatteryDrawable(context, frameColor);
        mCircleDrawable = new CircleBatteryDrawable(context, frameColor);
        atts.recycle();

        setupLayoutTransition();

        mBatteryShowPercent = getBatteryShowPercent();
        updateBatteryStyle();
        mDualToneHandler = new DualToneHandler(context);
        // Init to not dark at all.
        onDarkChanged(new ArrayList<Rect>(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        setClipChildren(false);
        setClipToPadding(false);
    }

    private void setupLayoutTransition() {
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(200);

        // Animates appearing/disappearing of the battery percentage text using fade-in/fade-out
        // and disables all other animation types
        ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
        transition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
        transition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

        ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
        transition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);

        transition.setAnimator(LayoutTransition.CHANGE_APPEARING, null);
        transition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, null);
        transition.setAnimator(LayoutTransition.CHANGING, null);

        setLayoutTransition(transition);
    }

    private void addOrRemoveIcon(Drawable style) {
        if (mBatteryIconView != null) {
            removeView(mBatteryIconView);
            mBatteryIconView = null;
        }

        if (style != null) {
            mBatteryIconView = new ImageView(getContext());
            mBatteryIconView.setImageDrawable(style);
            final MarginLayoutParams mlp = new MarginLayoutParams(
                    mBatteryStyle == BATTERY_STYLE_PORTRAIT ? getResources().getDimensionPixelSize(
                    R.dimen.status_bar_battery_icon_width) : getResources().getDimensionPixelSize(
                    R.dimen.status_bar_battery_icon_circle_width),
                    getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_height));
            mlp.setMargins(0, 0, 0,
                    getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
            addView(mBatteryIconView, mlp);
        }
    }

    protected void updateBatteryStyle() {
        mBatteryStyle = getBatteryStyle();
        updatePercentText();
        updateDrawable();
        scaleBatteryMeterViews();
        updatePercentView();
    }

    public void setForceShowPercent(boolean show) {
        setPercentShowMode(show ? MODE_ON : MODE_DEFAULT);
    }

    /**
     * Force a particular mode of showing percent
     *
     * 0 - No preference
     * 1 - Force on
     * 2 - Force off
     * 3 - Estimate
     * @param mode desired mode (none, on, off)
     */
    public void setPercentShowMode(@BatteryPercentMode int mode) {
        if (mode == mShowPercentMode) return;
        mShowPercentMode = mode;
        updateShowPercent();
        updatePercentText();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateBatteryStyle();
        mDrawable.notifyDensityChanged();
    }

    public void setColorsFromContext(Context context) {
        if (context == null) {
            return;
        }

        mDualToneHandler.setColorsFromContext(context);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /**
     * Update battery level
     *
     * @param level     int between 0 and 100 (representing percentage value)
     * @param pluggedIn whether the device is plugged in or not
     */
    public void onBatteryLevelChanged(@IntRange(from = 0, to = 100) int level, boolean pluggedIn) {
        boolean wasCharging = isCharging();
        mPluggedIn = pluggedIn;
        mLevel = level;
        boolean isCharging = isCharging();
        mDrawable.setCharging(isCharging);
        mDrawable.setBatteryLevel(level);
        mCircleDrawable.setCharging(pluggedIn);
        mCircleDrawable.setBatteryLevel(level);
        updatePercentText();
        if (wasCharging != isCharging) {
            updateShowPercent();
        }
    }

    void onPowerSaveChanged(boolean isPowerSave) {
        mPowerSaveEnabled = isPowerSave;
        mDrawable.setPowerSaveEnabled(isPowerSave);
        mCircleDrawable.setPowerSaveEnabled(isPowerSave);
        updateShowPercent();
    }

    void onIsBatteryDefenderChanged(boolean isBatteryDefender) {
        boolean valueChanged = mIsBatteryDefender != isBatteryDefender;
        mIsBatteryDefender = isBatteryDefender;

        if (!valueChanged) {
            return;
        }

        updateContentDescription();
        scaleBatteryMeterViews();
    }

    void onIsIncompatibleChargingChanged(boolean isIncompatibleCharging) {
        boolean valueChanged = mIsIncompatibleCharging != isIncompatibleCharging;
        mIsIncompatibleCharging = isIncompatibleCharging;
        if (valueChanged) {
            mDrawable.setCharging(isCharging());
            updateContentDescription();
        }
    }

    private TextView inflatePercentView() {
        return (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.battery_percentage_view, null);
    }

    private void addPercentView(TextView inflatedPercentView) {
        mBatteryPercentView = inflatedPercentView;

        if (mPercentageStyleId != 0) { // Only set if specified as attribute
            mBatteryPercentView.setTextAppearance(mPercentageStyleId);
        }
        float fontHeight = mBatteryPercentView.getPaint().getFontMetricsInt(null);
        mBatteryPercentView.setLineHeight(TypedValue.COMPLEX_UNIT_PX, fontHeight);
        if (mTextColor != 0) mBatteryPercentView.setTextColor(mTextColor);
        addView(mBatteryPercentView, new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                (int) Math.ceil(fontHeight)));
    }

    /**
     * Updates percent view by removing old one and reinflating if necessary
     */
    public void updatePercentView() {
        if (mBatteryPercentView != null) {
            removeView(mBatteryPercentView);
            mBatteryPercentView = null;
        }
        updateShowPercent();
    }

    /**
     * Sets the fetcher that should be used to get the estimated time remaining for the user's
     * battery.
     */
    void setBatteryEstimateFetcher(BatteryEstimateFetcher fetcher) {
        mBatteryEstimateFetcher = fetcher;
    }

    void setDisplayShieldEnabled(boolean displayShieldEnabled) {
        mDisplayShieldEnabled = displayShieldEnabled;
    }

    void updatePercentText() {
        if (mBatteryStateUnknown) {
            return;
        }

        if (mBatteryEstimateFetcher == null) {
            setPercentTextAtCurrentLevel();
            return;
        }

        if (mBatteryPercentView != null) {
            if (mShowPercentMode == MODE_ESTIMATE && !isCharging()) {
                mBatteryEstimateFetcher.fetchBatteryTimeRemainingEstimate(
                        (String estimate) -> {
                    if (mBatteryPercentView == null) {
                        return;
                    }
                    if (estimate != null && mShowPercentMode == MODE_ESTIMATE) {
                        mEstimateText = estimate;
                        mBatteryPercentView.setText(estimate);
                        updateContentDescription();
                    } else {
                        setPercentTextAtCurrentLevel();
                    }
                });
            } else {
                setPercentTextAtCurrentLevel();
            }
        } else {
            updateContentDescription();
        }
    }

    private void setPercentTextAtCurrentLevel() {
        if (mBatteryPercentView != null) {
            mEstimateText = null;
            String percentText = NumberFormat.getPercentInstance().format(mLevel / 100f);
            // Use the high voltage symbol ⚡ (u26A1 unicode) but prevent the system
            // to load its emoji colored variant with the uFE0E flag
            // only use it when there is no batt icon showing
            if (mPluggedIn && mBatteryStyle == BATTERY_STYLE_TEXT) {
                percentText = "\u26A1\uFE0E " + percentText;
            }
            // Setting text actually triggers a layout pass (because the text view is set to
            // wrap_content width and TextView always relayouts for this). Avoid needless
            // relayout if the text didn't actually change.
            if (TextUtils.equals(mBatteryPercentView.getText(), percentText)) {
                return;
            }
            mBatteryPercentView.setText(percentText);
        }

        updateContentDescription();
    }

    private void updateContentDescription() {
        Context context = getContext();

        String contentDescription;
        if (mBatteryStateUnknown) {
            contentDescription = context.getString(R.string.accessibility_battery_unknown);
        } else if (mShowPercentMode == MODE_ESTIMATE && !TextUtils.isEmpty(mEstimateText)) {
            contentDescription = context.getString(
                    mIsBatteryDefender
                            ? R.string.accessibility_battery_level_charging_paused_with_estimate
                            : R.string.accessibility_battery_level_with_estimate,
                    mLevel,
                    mEstimateText);
        } else if (mIsBatteryDefender) {
            contentDescription =
                    context.getString(R.string.accessibility_battery_level_charging_paused, mLevel);
        } else if (isCharging()) {
            contentDescription =
                    context.getString(R.string.accessibility_battery_level_charging, mLevel);
        } else {
            contentDescription = context.getString(R.string.accessibility_battery_level, mLevel);
        }

        setContentDescription(contentDescription);
    }

    void updateShowPercentSettings() {
        mBatteryShowPercent = getBatteryShowPercent();
    }

    void updateShowPercent() {
        final boolean isHidden = mBatteryStyle == BATTERY_STYLE_HIDDEN;
        if (isHidden) {
            if (mBatteryPercentView != null) {
                removeView(mBatteryPercentView);
                mBatteryPercentView = null;
            }
            return;
        }

        final boolean showing = mBatteryPercentView != null;
        final boolean drawPercentInside = mShowPercentMode == MODE_DEFAULT &&
                mBatteryShowPercent == BATTERY_PERCENT_INSIDE && !mPluggedIn &&
                (!mPowerSaveEnabled || mBatteryStyle == BATTERY_STYLE_CIRCLE);
        final boolean drawPercentOutside = mShowPercentMode == MODE_ESTIMATE ||
                mBatteryShowPercent == BATTERY_PERCENT_OUTSIDE ||
                mBatteryStyle == BATTERY_STYLE_TEXT ||
                mPluggedIn ||
                (mPowerSaveEnabled && mBatteryStyle == BATTERY_STYLE_PORTRAIT &&
                mBatteryShowPercent != BATTERY_PERCENT_HIDDEN);

        final boolean showOutside = drawPercentOutside && !mBatteryStateUnknown;

        if (showOutside) {
            mDrawable.showPercent(false);
            mCircleDrawable.setShowPercent(false);
            if (!showing) {
                addPercentView(inflatePercentView());
                updatePercentText();
            }
            if (mBatteryStyle == BATTERY_STYLE_TEXT) {
                mBatteryPercentView.setPaddingRelative(0, 0, 0, 0);
            } else {
                Resources res = getContext().getResources();
                mBatteryPercentView.setPaddingRelative(
                        res.getDimensionPixelSize(R.dimen.battery_level_padding_start), 0, 0, 0);
            }
        } else {
            mDrawable.showPercent(drawPercentInside);
            mCircleDrawable.setShowPercent(drawPercentInside);
            if (showing) {
                removeView(mBatteryPercentView);
                mBatteryPercentView = null;
            }
        }
    }

    private Drawable getUnknownStateDrawable() {
        if (mUnknownStateDrawable == null) {
            mUnknownStateDrawable = mContext.getDrawable(R.drawable.ic_battery_unknown);
            mUnknownStateDrawable.setTint(mTextColor);
        }

        return mUnknownStateDrawable;
    }

    void onBatteryUnknownStateChanged(boolean isUnknown) {
        if (mBatteryStateUnknown == isUnknown) {
            return;
        }

        mBatteryStateUnknown = isUnknown;
        updateContentDescription();

        if (mBatteryStateUnknown) {
            if (mBatteryIconView != null) {
                mBatteryIconView.setImageDrawable(getUnknownStateDrawable());
            }
        } else {
            updateDrawable();
        }

        updateShowPercent();
    }

    /**
     * Looks up the scale factor for status bar icons and scales the battery view by that amount.
     */
    void scaleBatteryMeterViews() {
        if (mBatteryIconView == null) {
            return;
        }

        Resources res = getContext().getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        int batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height);
        int batteryWidth = mBatteryStyle == BATTERY_STYLE_CIRCLE ?
                res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width) :
                res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width);
        float mainBatteryHeight = batteryHeight * iconScaleFactor;
        float mainBatteryWidth = batteryWidth * iconScaleFactor;

        boolean displayShield = mDisplayShieldEnabled && mIsBatteryDefender;
        float fullBatteryIconHeight =
                BatterySpecs.getFullBatteryHeight(mainBatteryHeight, displayShield);
        float fullBatteryIconWidth =
                BatterySpecs.getFullBatteryWidth(mainBatteryWidth, displayShield);

        int marginTop;
        if (displayShield) {
            // If the shield is displayed, we need some extra marginTop so that the bottom of the
            // main icon is still aligned with the bottom of all the other system icons.
            int shieldHeightAddition = Math.round(fullBatteryIconHeight - mainBatteryHeight);
            // However, the other system icons have some embedded bottom padding that the battery
            // doesn't have, so we shouldn't move the battery icon down by the full amount.
            // See b/258672854.
            marginTop = shieldHeightAddition
                    - res.getDimensionPixelSize(R.dimen.status_bar_battery_extra_vertical_spacing);
        } else {
            marginTop = 0;
        }

        int marginBottom = res.getDimensionPixelSize(R.dimen.battery_margin_bottom);

        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                Math.round(fullBatteryIconWidth),
                Math.round(fullBatteryIconHeight));
        scaledLayoutParams.setMargins(0, marginTop, 0, marginBottom);

        mDrawable.setDisplayShield(displayShield);
        mBatteryIconView.setLayoutParams(scaledLayoutParams);
        mBatteryIconView.invalidateDrawable(mDrawable);
    }

    private void updateDrawable() {
        switch (mBatteryStyle) {
            case BATTERY_STYLE_PORTRAIT:
                addOrRemoveIcon(mDrawable);
                break;
            case BATTERY_STYLE_CIRCLE:
                addOrRemoveIcon(mCircleDrawable);
                break;
            case BATTERY_STYLE_TEXT:
            case BATTERY_STYLE_HIDDEN:
                addOrRemoveIcon(null);
                break;
        }
    }

    private int getBatteryStyle() {
        return Settings.System.getIntForUser(getContext().getContentResolver(),
                STATUS_BAR_BATTERY_STYLE, BATTERY_STYLE_PORTRAIT,
                UserHandle.USER_CURRENT);
    }

    private int getBatteryShowPercent() {
        return Settings.System.getIntForUser(getContext().getContentResolver(),
                STATUS_BAR_SHOW_BATTERY_PERCENT, BATTERY_PERCENT_HIDDEN,
                UserHandle.USER_CURRENT);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        if (mIsStaticColor) return;
        float intensity = DarkIconDispatcher.isInAreas(areas, this) ? darkIntensity : 0;
        int nonAdaptedSingleToneColor = mDualToneHandler.getSingleColor(intensity);
        int nonAdaptedForegroundColor = mDualToneHandler.getFillColor(intensity);
        int nonAdaptedBackgroundColor = mDualToneHandler.getBackgroundColor(intensity);

        updateColors(nonAdaptedForegroundColor, nonAdaptedBackgroundColor,
                nonAdaptedSingleToneColor);
    }

    public void setStaticColor(boolean isStaticColor) {
        mIsStaticColor = isStaticColor;
    }

    /**
     * Sets icon and text colors. This will be overridden by {@code onDarkChanged} events,
     * if registered.
     *
     * @param foregroundColor
     * @param backgroundColor
     * @param singleToneColor
     */
    public void updateColors(int foregroundColor, int backgroundColor, int singleToneColor) {
        mDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mCircleDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mTextColor = singleToneColor;
        if (mBatteryPercentView != null) {
            mBatteryPercentView.setTextColor(singleToneColor);
        }

        if (mUnknownStateDrawable != null) {
            mUnknownStateDrawable.setTint(singleToneColor);
        }
    }

    @VisibleForTesting
    boolean isCharging() {
        return mPluggedIn && !mIsIncompatibleCharging;
    }

    public void dump(PrintWriter pw, String[] args) {
        String powerSave = mDrawable == null ? null : mDrawable.getPowerSaveEnabled() + "";
        String displayShield = mDrawable == null ? null : mDrawable.getDisplayShield() + "";
        String charging = mDrawable == null ? null : mDrawable.getCharging() + "";
        CharSequence percent = mBatteryPercentView == null ? null : mBatteryPercentView.getText();
        pw.println("  BatteryMeterView:");
        pw.println("    mDrawable.getPowerSave: " + powerSave);
        pw.println("    mDrawable.getDisplayShield: " + displayShield);
        pw.println("    mDrawable.getCharging: " + charging);
        pw.println("    mBatteryPercentView.getText(): " + percent);
        pw.println("    mTextColor: #" + Integer.toHexString(mTextColor));
        pw.println("    mBatteryStateUnknown: " + mBatteryStateUnknown);
        pw.println("    mIsIncompatibleCharging: " + mIsIncompatibleCharging);
        pw.println("    mPluggedIn: " + mPluggedIn);
        pw.println("    mLevel: " + mLevel);
        pw.println("    mMode: " + mShowPercentMode);
    }

    @VisibleForTesting
    CharSequence getBatteryPercentViewText() {
        return mBatteryPercentView.getText();
    }

    @VisibleForTesting
    TextView getBatteryPercentView() {
        return mBatteryPercentView;
    }

    /** An interface that will fetch the estimated time remaining for the user's battery. */
    public interface BatteryEstimateFetcher {
        void fetchBatteryTimeRemainingEstimate(
                BatteryController.EstimateFetchCompletion completion);
    }
}

