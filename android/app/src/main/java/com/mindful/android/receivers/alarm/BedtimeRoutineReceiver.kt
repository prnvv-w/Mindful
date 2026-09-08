/*
 *
 *  *
 *  *  * Copyright (c) 2024 Mindful (https://github.com/akaMrNagar/Mindful)
 *  *  * Author : Pawan Nagar (https://github.com/akaMrNagar)
 *  *  *
 *  *  * This source code is licensed under the GPL-2.0 license license found in the
 *  *  * LICENSE file in the root directory of this source tree.
 *  *
 *
 */
package com.mindful.android.receivers.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.mindful.android.AppConstants
import com.mindful.android.R
import com.mindful.android.enums.DndWakeLock
import com.mindful.android.generics.SafeServiceConnection
import com.mindful.android.helpers.AlarmTasksSchedulingHelper.scheduleBedtimeRoutineTasks
import com.mindful.android.helpers.device.NotificationHelper
import com.mindful.android.helpers.storage.SharedPrefsHelper
import com.mindful.android.models.BedtimeSchedule
import com.mindful.android.services.tracking.MindfulTrackerService
import com.mindful.android.utils.AppUtils
import com.mindful.android.utils.DateTimeUtils
import com.mindful.android.utils.ThreadUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BedtimeRoutineReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "Mindful.BedtimeRoutineReceiver"
        const val ACTION_ALERT_BEDTIME: String = "com.mindful.android.action.alertBedtime"
        const val ACTION_START_BEDTIME: String = "com.mindful.android.action.startBedtime"
        const val ACTION_STOP_BEDTIME: String = "com.mindful.android.action.stopBedtime"
        const val EXTRA_BEDTIME_SETTINGS_JSON = "com.mindful.android.extra.bedtimeSettingsJson"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALERT_BEDTIME, ACTION_START_BEDTIME, ACTION_STOP_BEDTIME -> {
                /// Schedule worker with REPLACE policy so alarms are never dropped
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "${TAG}_${System.currentTimeMillis()}",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequest.Builder(BedtimeRoutineWorker::class.java)
                        .setInputData(
                            Data.Builder()
                                .putString("action", intent.action)
                                .putString(
                                    EXTRA_BEDTIME_SETTINGS_JSON,
                                    intent.extras?.getString(EXTRA_BEDTIME_SETTINGS_JSON) ?: ""
                                )
                                .build()
                        )
                        .build()
                )
            }
        }
    }


    class BedtimeRoutineWorker(
        private val context: Context,
        params: WorkerParameters,
    ) : Worker(context, params) {
        private val jsonBedtimeSettings = inputData.getString(EXTRA_BEDTIME_SETTINGS_JSON) ?: ""
        private val bedtimeSchedule = BedtimeSchedule.fromJson(jsonBedtimeSettings)
        private val canStartRoutineToday: Boolean =
            bedtimeSchedule.scheduleDays[DateTimeUtils.zeroIndexedDayOfWeek()]

        private val trackerServiceConn = SafeServiceConnection(
            context = context,
            serviceClass = MindfulTrackerService::class.java
        )

        override fun doWork(): Result {
            try {
                val action = inputData.getString("action")

                when (action) {
                    ACTION_ALERT_BEDTIME -> pushAlertNotification(context.getString(R.string.bedtime_upcoming_notification_info))
                    ACTION_START_BEDTIME -> startBedtimeRoutine()
                    ACTION_STOP_BEDTIME -> {
                        stopBedtimeRoutine()

                        // Reschedule bedtime tasks for next day
                        ThreadUtils.runOnMainThread(1000L) {
                            scheduleBedtimeRoutineTasks(
                                context,
                                jsonBedtimeSettings
                            )
                        }
                    }
                }
                return Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "doWork: Error during work execution", e)
                SharedPrefsHelper.insertCrashLogToPrefs(context, e)
                return Result.failure()
            }
        }

        private fun startBedtimeRoutine() {
            if (!canStartRoutineToday) return

            val latch = CountDownLatch(1)
            trackerServiceConn.setOnConnectedCallback { service: MindfulTrackerService ->
                with(service) {
                    getRestrictionManager.updateBedtimeApps(bedtimeSchedule.distractingApps)
                    getLaunchTrackingManager.detectActiveAppForBedtime()
                }
                latch.countDown()
            }
            trackerServiceConn.startAndBind()

            // Wait up to 5 seconds for service connection to complete before finishing worker
            try {
                latch.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Log.e(TAG, "startBedtimeRoutine: Latch interrupted", e)
            }

            // Start DND if needed
            if (bedtimeSchedule.shouldStartDnd) NotificationHelper.toggleDnd(
                context,
                DndWakeLock.BEDTIME_MODE,
                true
            )
            pushAlertNotification(context.getString(R.string.bedtime_started_notification_info))
        }

        private fun stopBedtimeRoutine() {
            val latch = CountDownLatch(1)
            trackerServiceConn.setOnConnectedCallback { service: MindfulTrackerService ->
                service.getRestrictionManager.updateBedtimeApps(null)
                latch.countDown()
            }
            trackerServiceConn.bindService()

            try {
                latch.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Log.e(TAG, "stopBedtimeRoutine: Latch interrupted", e)
            }

            // Stop DND if needed
            if (bedtimeSchedule.shouldStartDnd) NotificationHelper.toggleDnd(
                context,
                DndWakeLock.BEDTIME_MODE,
                false
            )
            pushAlertNotification(context.getString(R.string.bedtime_ended_notification_info))
        }

        private fun pushAlertNotification(alert: String) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(
                AppConstants.BEDTIME_ROUTINE_NOTIFICATION_ID,
                NotificationCompat.Builder(
                    context,
                    NotificationHelper.BEDTIME_CHANNEL_ID
                )
                    .setSmallIcon(R.drawable.ic_mindful_notification)
                    .setOngoing(false)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(
                        AppUtils.getPendingIntentForMindfulUri(
                            context,
                            "com.mindful.android://open/home?tab=3",
                        )
                    )
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(alert)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alert))
                    .build()
            )
        }
    }
}

