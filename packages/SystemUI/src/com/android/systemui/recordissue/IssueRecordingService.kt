/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.recordissue

import android.app.IActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import com.android.internal.logging.UiEventLogger
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.qualifiers.LongRunning
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.res.R
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.screenrecord.RecordingService
import com.android.systemui.screenrecord.RecordingServiceStrings
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import java.util.concurrent.Executor
import javax.inject.Inject

class IssueRecordingService
@Inject
constructor(
    controller: RecordingController,
    @LongRunning private val bgExecutor: Executor,
    @Main handler: Handler,
    uiEventLogger: UiEventLogger,
    notificationManager: NotificationManager,
    featureFlags: FeatureFlags,
    activityStarter: ActivityStarter,
    userContextProvider: UserContextProvider,
    keyguardDismissUtil: KeyguardDismissUtil,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val panelInteractor: PanelInteractor,
    private val traceurMessageSender: TraceurMessageSender,
    private val issueRecordingState: IssueRecordingState,
    private val iActivityManager: IActivityManager,
) :
    RecordingService(
        controller,
        bgExecutor,
        handler,
        uiEventLogger,
        notificationManager,
        featureFlags,
        dialogTransitionAnimator,
        activityStarter,
        userContextProvider,
        keyguardDismissUtil
    ) {

    override fun getTag(): String = TAG

    override fun getChannelId(): String = CHANNEL_ID

    override fun provideRecordingServiceStrings(): RecordingServiceStrings = IrsStrings(resources)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                bgExecutor.execute {
                    traceurMessageSender.startTracing(issueRecordingState.traceType)
                }
                issueRecordingState.isRecording = true
                if (!issueRecordingState.recordScreen) {
                    // If we don't want to record the screen, the ACTION_SHOW_START_NOTIF action
                    // will circumvent the RecordingService's screen recording start code.
                    return super.onStartCommand(Intent(ACTION_SHOW_START_NOTIF), flags, startId)
                }
            }
            ACTION_STOP,
            ACTION_STOP_NOTIF -> {
                // ViewCapture needs to save it's data before it is disabled, or else the data will
                // be lost. This is expected to change in the near future, and when that happens
                // this line should be removed.
                bgExecutor.execute { traceurMessageSender.stopTracing() }
                issueRecordingState.isRecording = false
            }
            ACTION_SHARE -> {
                bgExecutor.execute {
                    mNotificationManager.cancelAsUser(
                        null,
                        mNotificationId,
                        UserHandle(mUserContextTracker.userContext.userId)
                    )

                    val screenRecording = intent.getParcelableExtra(EXTRA_PATH, Uri::class.java)
                    if (issueRecordingState.takeBugreport) {
                        iActivityManager.requestBugReportWithExtraAttachment(screenRecording)
                    } else {
                        traceurMessageSender.shareTraces(applicationContext, screenRecording)
                    }
                }

                dialogTransitionAnimator.disableAllCurrentDialogsExitAnimations()
                panelInteractor.collapsePanels()

                // Unlike all other actions, action_share has different behavior for the screen
                // recording qs tile than it does for the record issue qs tile. Return sticky to
                // avoid running any of the base class' code for this action.
                return START_STICKY
            }
            else -> {}
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        private const val TAG = "IssueRecordingService"
        private const val CHANNEL_ID = "issue_record"

        /**
         * Get an intent to stop the issue recording service.
         *
         * @param context Context from the requesting activity
         * @return
         */
        fun getStopIntent(context: Context): Intent =
            Intent(context, IssueRecordingService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(Intent.EXTRA_USER_HANDLE, context.userId)

        /**
         * Get an intent to start the issue recording service.
         *
         * @param context Context from the requesting activity
         */
        fun getStartIntent(context: Context): Intent =
            Intent(context, IssueRecordingService::class.java).setAction(ACTION_START)
    }
}

private class IrsStrings(private val res: Resources) : RecordingServiceStrings(res) {
    override val title
        get() = res.getString(R.string.issuerecord_title)

    override val notificationChannelDescription
        get() = res.getString(R.string.issuerecord_channel_description)

    override val startErrorResId
        get() = R.string.issuerecord_start_error

    override val startError
        get() = res.getString(R.string.issuerecord_start_error)

    override val saveErrorResId
        get() = R.string.issuerecord_save_error

    override val saveError
        get() = res.getString(R.string.issuerecord_save_error)

    override val ongoingRecording
        get() = res.getString(R.string.issuerecord_ongoing_screen_only)

    override val backgroundProcessingLabel
        get() = res.getString(R.string.issuerecord_background_processing_label)

    override val saveTitle
        get() = res.getString(R.string.issuerecord_save_title)
}
