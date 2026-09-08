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
package com.mindful.android.helpers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.mindful.android.AppConstants
import com.mindful.android.generics.SafeServiceConnection
import com.mindful.android.models.BedtimeSchedule
import com.mindful.android.models.NotificationSettings
import com.mindful.android.receivers.alarm.BedtimeRoutineReceiver
import com.mindful.android.receivers.alarm.BedtimeRoutineReceiver.Companion.EXTRA_BEDTIME_SETTINGS_JSON
import com.mindful.android.receivers.alarm.MidnightResetReceiver
import com.mindful.android.receivers.alarm.NotificationBatchReceiver
import com.mindful.android.receivers.alarm.NotificationBatchReceiver.Companion.EXTRA_NOTIFICATION_SETTINGS_JSON
import com.mindful.android.receivers.alarm.NotificationBatchReceiver.NotificationBatchWorker
import com.mindful.android.services.tracking.MindfulTrackerService
import com.mindful.android.utils.DateTimeUtils.todToTodayCal
import com.mindful.android.utils.Utils
import java.util.Calendar
import java.util.Date

/**
 * Helper class for scheduling alarm tasks related to bedtime routines and midnight resets.
 */
object AlarmTasksSchedulingHelper {
    private const val TAG = "Mindful.AlarmTasksSchedulingHelper"
    private const val MIDNIGHT_RESET_ALARM_ID = 101

    // 🔥 UNIQUE ALARM CODES FOR EACH BEDTIME STATE
    private const val BEDTIME_ALERT_ALARM_ID = 1021
    private const val BEDTIME_START_ALARM_ID = 1022
    private const val BEDTIME_STOP_ALARM_ID = 1023

    private const val NOTIFICATION_BATCH_ALARM_ID = 103

    fun scheduleMidnightResetTask(context: Context, checkBeforeScheduling: Boolean) {
        if (checkBeforeScheduling) {
            val intent =
                Intent(context.applicationContext, MidnightResetReceiver::class.java).setAction(
                    MidnightResetReceiver.ACTION_START_MIDNIGHT_RESET
                )
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                MIDNIGHT_RESET_ALARM_ID,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )

            if (pendingIntent != null) {
                Log.d(TAG, "scheduleMidnightTask: Midnight reset task is already scheduled")
                return
            }
        }

        val cal = Calendar.getInstance()
        cal[Calendar.HOUR_OF_DAY] = 0
        cal[Calendar.MINUTE] = 0
        cal[Calendar.SECOND] = 3
        cal.add(Calendar.DATE, 1)

        scheduleOrUpdateExactAlarmTask(
            context = context,
            receiverClass = MidnightResetReceiver::class.java,
            intentAction = MidnightResetReceiver.ACTION_START_MIDNIGHT_RESET,
            requestCode = MIDNIGHT_RESET_ALARM_ID,
            epochTimeMs = cal.timeInMillis
        )
        Log.d(
            TAG,
            "scheduleMidnightTask: Midnight reset task scheduled successfully for " + cal.time
        )
    }

    fun scheduleBedtimeRoutineTasks(context: Context, jsonBedtimeSettings: String) {
        val bedtimeSchedule = BedtimeSchedule.fromJson(jsonBedtimeSettings)
        val extraMap = mapOf(
            EXTRA_BEDTIME_SETTINGS_JSON to jsonBedtimeSettings
        )

        val nowInMs = System.currentTimeMillis()
        var startTimeMs = todToTodayCal(bedtimeSchedule.scheduleStartTime).timeInMillis
        var endTimeMs = startTimeMs + (bedtimeSchedule.scheduleDurationInMins * 60 * 1000L)
        var alertTimeMs = startTimeMs - (30 * 60 * 1000L)

        // Agar poori routine khatam ho chuki hai, agle din ke liye schedule karo
        if (endTimeMs < nowInMs) {
            alertTimeMs += AppConstants.ONE_DAY_IN_MS
            startTimeMs += AppConstants.ONE_DAY_IN_MS
            endTimeMs += AppConstants.ONE_DAY_IN_MS
        }

        // 1. Bedtime Alert
        if (alertTimeMs > nowInMs) {
            scheduleOrUpdateExactAlarmTask(
                context = context,
                receiverClass = BedtimeRoutineReceiver::class.java,
                intentAction = BedtimeRoutineReceiver.ACTION_ALERT_BEDTIME,
                epochTimeMs = alertTimeMs,
                requestCode = BEDTIME_ALERT_ALARM_ID,
                extraMap = extraMap,
            )
        }

        // 2. Bedtime Start
        if (startTimeMs > nowInMs) {
            scheduleOrUpdateExactAlarmTask(
                context = context,
                receiverClass = BedtimeRoutineReceiver::class.java,
                intentAction = BedtimeRoutineReceiver.ACTION_START_BEDTIME,
                epochTimeMs = startTimeMs,
                requestCode = BEDTIME_START_ALARM_ID,
                extraMap = extraMap,
            )
        } else if (nowInMs in startTimeMs until endTimeMs) {
            // Agar bedtime chal rahi hai (current time is between start and end), turant start trigger karo
            val startIntent = Intent(context.applicationContext, BedtimeRoutineReceiver::class.java)
                .setAction(BedtimeRoutineReceiver.ACTION_START_BEDTIME)
                .putExtra(EXTRA_BEDTIME_SETTINGS_JSON, jsonBedtimeSettings)
            context.sendBroadcast(startIntent)
        }

        // 3. Bedtime Stop
        if (endTimeMs > nowInMs) {
            scheduleOrUpdateExactAlarmTask(
                context = context,
                receiverClass = BedtimeRoutineReceiver::class.java,
                intentAction = BedtimeRoutineReceiver.ACTION_STOP_BEDTIME,
                epochTimeMs = endTimeMs,
                requestCode = BEDTIME_STOP_ALARM_ID,
                extraMap = extraMap,
            )
        }

        Log.d(
            TAG, """
                 scheduleBedtimeStartTask: Bedtime routine tasks scheduled successfully for - 
                 alert: ${if (alertTimeMs > nowInMs) "" else "(skipping) "}${Date(alertTimeMs)}
                 start: ${Date(startTimeMs)}
                 end: ${Date(endTimeMs)}
                 """.trimIndent()
        )
    }

    fun cancelBedtimeRoutineTasks(context: Context) {
        cancelExactAlarmTasks(
            context = context,
            receiverClass = BedtimeRoutineReceiver::class.java,
            requestCode = BEDTIME_ALERT_ALARM_ID,
            intentActions = listOf(BedtimeRoutineReceiver.ACTION_ALERT_BEDTIME),
        )
        cancelExactAlarmTasks(
            context = context,
            receiverClass = BedtimeRoutineReceiver::class.java,
            requestCode = BEDTIME_START_ALARM_ID,
            intentActions = listOf(BedtimeRoutineReceiver.ACTION_START_BEDTIME),
        )
        cancelExactAlarmTasks(
            context = context,
            receiverClass = BedtimeRoutineReceiver::class.java,
            requestCode = BEDTIME_STOP_ALARM_ID,
            intentActions = listOf(BedtimeRoutineReceiver.ACTION_STOP_BEDTIME),
        )

        runCatching {
            if (Utils.isServiceRunning(context, MindfulTrackerService::class.java)) {
                val conn = SafeServiceConnection(context, MindfulTrackerService::class.java)
                conn.setOnConnectedCallback { service ->
                    service.getRestrictionManager.updateBedtimeApps(null)
                }
                conn.bindService()
                conn.unBindService()
            }
        }
        Log.d(TAG, "cancelBedtimeRoutineTasks: Bedtime routine tasks cancelled successfully")
    }

    fun scheduleNotificationBatchTask(context: Context, jsonNotificationSettings: String) {
        val settings = NotificationSettings.fromJson(jsonNotificationSettings)
        if (settings.schedules.isEmpty()) return

        val now = System.currentTimeMillis()
        var nextAlarmTimeMs: Long? = null

        for (schedule in settings.schedules) {
            val currentTime = todToTodayCal(schedule.todMinutes).timeInMillis
            if (currentTime > now) {
                nextAlarmTimeMs = currentTime
                break
            }
        }

        nextAlarmTimeMs = nextAlarmTimeMs
            ?: (todToTodayCal(settings.schedules[0].todMinutes).timeInMillis + AppConstants.ONE_DAY_IN_MS)

        scheduleOrUpdateExactAlarmTask(
            context = context,
            receiverClass = NotificationBatchReceiver::class.java,
            intentAction = NotificationBatchReceiver.ACTION_PUSH_BATCH,
            requestCode = NOTIFICATION_BATCH_ALARM_ID,
            epochTimeMs = nextAlarmTimeMs,
            extraMap = mapOf(
                EXTRA_NOTIFICATION_SETTINGS_JSON to jsonNotificationSettings
            ),
        )
    }

    fun cancelNotificationBatchTask(context: Context) {
        cancelExactAlarmTasks(
            context = context,
            receiverClass = NotificationBatchWorker::class.java,
            requestCode = NOTIFICATION_BATCH_ALARM_ID,
            intentActions = listOf(NotificationBatchReceiver.ACTION_PUSH_BATCH)
        )
    }

    private fun scheduleOrUpdateExactAlarmTask(
        context: Context,
        receiverClass: Class<*>,
        intentAction: String,
        requestCode: Int,
        epochTimeMs: Long,
        extraMap: Map<String, String>? = null,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context.applicationContext, receiverClass).setAction(intentAction)
        extraMap?.let {
            it.forEach { entry ->
                intent.putExtra(entry.key, entry.value)
            }
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    epochTimeMs,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    epochTimeMs,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                epochTimeMs,
                pendingIntent
            )
        }
    }

    private fun cancelExactAlarmTasks(
        context: Context,
        receiverClass: Class<*>,
        requestCode: Int,
        intentActions: List<String>,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        for (action in intentActions) {
            val intent = Intent(context.applicationContext, receiverClass).setAction(action)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }
}

