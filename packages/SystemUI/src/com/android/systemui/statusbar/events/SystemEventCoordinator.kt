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

package com.android.systemui.statusbar.events

import android.annotation.IntRange
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.DeviceConfig
import android.provider.DeviceConfig.NAMESPACE_PRIVACY
import android.provider.Settings
import com.android.systemui.battery.BatteryMeterView.BATTERY_STYLE_HIDDEN
import com.android.systemui.battery.BatteryMeterView.BATTERY_STYLE_PORTRAIT
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.State
import com.android.systemui.privacy.PrivacyChipBuilder
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.sun.provider.SettingsExt.System.STATUS_BAR_BATTERY_STYLE

/**
 * Listens for system events (battery, privacy, connectivity) and allows listeners to show status
 * bar animations when they happen
 */
@SysUISingleton
class SystemEventCoordinator
@Inject
constructor(
    private val systemClock: SystemClock,
    private val batteryController: BatteryController,
    private val privacyController: PrivacyItemController,
    private val systemSettings: SystemSettings,
    private val userTracker: UserTracker,
    private val context: Context,
    @Application private val appScope: CoroutineScope,
    @Background private val bgExecutor: Executor,
    connectedDisplayInteractor: ConnectedDisplayInteractor
) {
    private val onDisplayConnectedFlow =
        connectedDisplayInteractor.connectedDisplayAddition

    private var connectedDisplayCollectionJob: Job? = null
    private lateinit var scheduler: SystemStatusAnimationScheduler

    private var batteryHidden = false
    private val settingsObserver = object: ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            when (uri?.lastPathSegment) {
                STATUS_BAR_BATTERY_STYLE -> batteryHidden = isBatteryHidden()
            }
        }
    }
    private val userTrackerCallback = object: UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) {
            batteryHidden = isBatteryHidden()
        }
    }

    fun startObserving() {
        batteryController.addCallback(batteryStateListener)
        privacyController.addCallback(privacyStateListener)
        startConnectedDisplayCollection()

        systemSettings.registerContentObserverForUserSync(
                STATUS_BAR_BATTERY_STYLE,
                settingsObserver, UserHandle.USER_ALL);
        batteryHidden = isBatteryHidden()
        userTracker.addCallback(userTrackerCallback, bgExecutor)
    }

    fun stopObserving() {
        userTracker.removeCallback(userTrackerCallback)
        systemSettings.unregisterContentObserverSync(settingsObserver)

        batteryController.removeCallback(batteryStateListener)
        privacyController.removeCallback(privacyStateListener)
        connectedDisplayCollectionJob?.cancel()
    }

    fun attachScheduler(s: SystemStatusAnimationScheduler) {
        this.scheduler = s
    }

    fun notifyPluggedIn(@IntRange(from = 0, to = 100) batteryLevel: Int) {
        if (batteryHidden) {
            return
        }
        scheduler.onStatusEvent(BatteryEvent(batteryLevel))
    }

    fun notifyPrivacyItemsEmpty() {
        scheduler.removePersistentDot()
    }

    fun notifyPrivacyItemsChanged(showAnimation: Boolean = true) {
        val event = PrivacyEvent(showAnimation)
        event.privacyItems = privacyStateListener.currentPrivacyItems
        event.contentDescription = run {
            val items = PrivacyChipBuilder(context, event.privacyItems).joinTypes()
            context.getString(
                    R.string.ongoing_privacy_chip_content_multiple_apps, items)
        }
        scheduler.onStatusEvent(event)
    }

    private fun startConnectedDisplayCollection() {
        val connectedDisplayEvent = ConnectedDisplayEvent().apply {
            contentDescription = context.getString(R.string.connected_display_icon_desc)
        }
        connectedDisplayCollectionJob =
                onDisplayConnectedFlow
                        .onEach { scheduler.onStatusEvent(connectedDisplayEvent) }
                        .launchIn(appScope)
    }

    private fun isBatteryHidden(): Boolean {
        return systemSettings.getIntForUser(STATUS_BAR_BATTERY_STYLE,
                BATTERY_STYLE_PORTRAIT, userTracker.userId) == BATTERY_STYLE_HIDDEN
    }

    private val batteryStateListener = object : BatteryController.BatteryStateChangeCallback {
        private var plugged = false
        private var stateKnown = false
        override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
            if (!stateKnown) {
                stateKnown = true
                plugged = pluggedIn
                notifyListeners(level)
                return
            }

            if (plugged != pluggedIn) {
                plugged = pluggedIn
                notifyListeners(level)
            }
        }

        private fun notifyListeners(@IntRange(from = 0, to = 100) batteryLevel: Int) {
            // We only care about the plugged in status
            if (plugged) notifyPluggedIn(batteryLevel)
        }
    }

    private val privacyStateListener = object : PrivacyItemController.Callback {
        var currentPrivacyItems = listOf<PrivacyItem>()
        var previousPrivacyItems = listOf<PrivacyItem>()
        var timeLastEmpty = systemClock.elapsedRealtime()

        override fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>) {
            if (uniqueItemsMatch(privacyItems, currentPrivacyItems)) {
                return
            } else if (privacyItems.isEmpty()) {
                previousPrivacyItems = currentPrivacyItems
                timeLastEmpty = systemClock.elapsedRealtime()
            }

            currentPrivacyItems = privacyItems
            notifyListeners()
        }

        private fun notifyListeners() {
            if (currentPrivacyItems.isEmpty()) {
                notifyPrivacyItemsEmpty()
            } else {
                val showAnimation = isChipAnimationEnabled() &&
                    (!uniqueItemsMatch(currentPrivacyItems, previousPrivacyItems) ||
                    systemClock.elapsedRealtime() - timeLastEmpty >= DEBOUNCE_TIME)
                notifyPrivacyItemsChanged(showAnimation)
            }
        }

        // Return true if the lists contain the same permission groups, used by the same UIDs
        private fun uniqueItemsMatch(one: List<PrivacyItem>, two: List<PrivacyItem>): Boolean {
            return one.map { it.application.uid to it.privacyType.permGroupName }.toSet() ==
                two.map { it.application.uid to it.privacyType.permGroupName }.toSet()
        }

        private fun isChipAnimationEnabled(): Boolean {
            val defaultValue =
                context.resources.getBoolean(R.bool.config_enablePrivacyChipAnimation)
            return DeviceConfig.getBoolean(NAMESPACE_PRIVACY, CHIP_ANIMATION_ENABLED, defaultValue)
        }
    }
}

private const val DEBOUNCE_TIME = 3000L
private const val CHIP_ANIMATION_ENABLED = "privacy_chip_animation_enabled"
