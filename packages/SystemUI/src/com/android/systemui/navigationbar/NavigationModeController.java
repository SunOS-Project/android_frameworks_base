/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar;

import static android.content.Intent.ACTION_OVERLAY_CHANGED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.pm.PackageManager;
import android.content.res.ApkAssets;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import org.sun.systemui.statusbar.phone.NavbarAppearanceController;

/**
 * Controller for tracking the current navigation bar mode.
 */
@SysUISingleton
public class NavigationModeController implements Dumpable {

    private static final String TAG = NavigationModeController.class.getSimpleName();
    private static final boolean DEBUG = true;

    public interface ModeChangedListener {
        void onNavigationModeChanged(int mode);
    }

    private final Context mContext;
    private Context mCurrentUserContext;
    private final IOverlayManager mOverlayManager;
    private final Executor mUiBgExecutor;
    private final NavbarAppearanceController mNavbarAppearanceController;
    private final UserTracker mUserTracker;

    private ArrayList<ModeChangedListener> mListeners = new ArrayList<>();

    private final UserTracker.Callback mUserTrackerCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, @NonNull Context userContext) {
            if (DEBUG) {
                Log.d(TAG, "onUserChanged: "
                        + newUser);
            }

            updateCurrentInteractionMode(true /* notify */);
        }
    };

    // The primary user SysUI process doesn't get AppInfo changes from overlay package changes for
    // the secondary user (b/158613864), so we need to update the interaction mode here as well
    // as a fallback if we don't receive the configuration change
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.d(TAG, "ACTION_OVERLAY_CHANGED");
            }
            updateCurrentInteractionMode(true /* notify */);
        }
    };


    @Inject
    public NavigationModeController(Context context,
            ConfigurationController configurationController,
            NavbarAppearanceController navbarAppearanceController,
            UserTracker userTracker,
            @Main Executor mainExecutor,
            @UiBackground Executor uiBgExecutor,
            DumpManager dumpManager) {
        mContext = context;
        mCurrentUserContext = context;
        mNavbarAppearanceController = navbarAppearanceController;
        mUserTracker = userTracker;
        mUserTracker.addCallback(mUserTrackerCallback, mainExecutor);
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        mUiBgExecutor = uiBgExecutor;
        dumpManager.registerDumpable(getClass().getSimpleName(), this);

        IntentFilter overlayFilter = new IntentFilter(ACTION_OVERLAY_CHANGED);
        overlayFilter.addDataScheme("package");
        overlayFilter.addDataSchemeSpecificPart("android", PatternMatcher.PATTERN_LITERAL);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, overlayFilter, null, null);

        configurationController.addCallback(new ConfigurationController.ConfigurationListener() {
            @Override
            public void onThemeChanged() {
                if (DEBUG) {
                    Log.d(TAG, "onOverlayChanged");
                }
                updateCurrentInteractionMode(true /* notify */);
            }
        });

        updateCurrentInteractionMode(false /* notify */);
    }

    public void updateCurrentInteractionMode(boolean notify) {
        Trace.beginSection("NMC#updateCurrentInteractionMode");
        mCurrentUserContext = getCurrentUserContext();
        int mode = getCurrentInteractionMode(mCurrentUserContext);
        mUiBgExecutor.execute(() ->
            Settings.Secure.putString(mCurrentUserContext.getContentResolver(),
                    Secure.NAVIGATION_MODE, String.valueOf(mode)));
        if (DEBUG) {
            Log.d(TAG, "updateCurrentInteractionMode: mode=" + mode);
        }

        if (notify) {
            for (int i = 0; i < mListeners.size(); i++) {
                mListeners.get(i).onNavigationModeChanged(mode);
            }
        }
        Trace.endSection();
    }

    public int addListener(ModeChangedListener listener) {
        mListeners.add(listener);
        return getCurrentInteractionMode(mCurrentUserContext);
    }

    public void removeListener(ModeChangedListener listener) {
        mListeners.remove(listener);
    }

    public boolean getImeDrawsImeNavBar() {
        return mCurrentUserContext.getResources().getBoolean(
                com.android.internal.R.bool.config_imeDrawsImeNavBar);
    }

    public NavbarAppearanceController getAppearanceController() {
        return mNavbarAppearanceController;
    }

    private int getCurrentInteractionMode(Context context) {
        int mode = context.getResources().getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode);
        if (DEBUG) {
            Log.d(TAG, "getCurrentInteractionMode: mode=" + mode
                    + " contextUser=" + context.getUserId());
        }
        return mode;
    }

    public Context getCurrentUserContext() {
        int userId = mUserTracker.getUserId();
        if (DEBUG) {
            Log.d(TAG, "getCurrentUserContext: contextUser=" + mContext.getUserId()
                    + " currentUser=" + userId);
        }
        if (mContext.getUserId() == userId) {
            return mContext;
        }
        try {
            return mContext.createPackageContextAsUser(mContext.getPackageName(),
                    0 /* flags */, UserHandle.of(userId));
        } catch (PackageManager.NameNotFoundException e) {
            // Never happens for the sysui package
            Log.e(TAG, "Failed to create package context", e);
            return null;
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("NavigationModeController:");
        pw.println("  mode=" + getCurrentInteractionMode(mCurrentUserContext));
        String defaultOverlays = "";
        try {
            defaultOverlays = String.join(", ", mOverlayManager.getDefaultOverlayPackages());
        } catch (RemoteException e) {
            defaultOverlays = "failed_to_fetch";
        }
        pw.println("  defaultOverlays=" + defaultOverlays);
    }
}
