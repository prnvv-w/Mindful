package com.mindful.android.services.tracking

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.WorkerThread
import com.mindful.android.AppConstants
import com.mindful.android.R
import com.mindful.android.enums.RestrictionType
import com.mindful.android.generics.ServiceBinder
import com.mindful.android.helpers.device.NotificationHelper
import com.mindful.android.helpers.storage.SharedPrefsHelper

class MindfulTrackerService : Service() {
    companion object {
        private const val TAG = "Mindful.MindfulTrackerService"
    }

    private val mBinder = ServiceBinder(this@MindfulTrackerService)

    private lateinit var overlayManager: OverlayManager
    private lateinit var reminderManager: ReminderManager

    private lateinit var restrictionManager: RestrictionManager
    val getRestrictionManager get() = restrictionManager

    private lateinit var launchTrackingManager: LaunchTrackingManager
    val getLaunchTrackingManager get() = launchTrackingManager

    // 🔥 GATEKEEPER SESSION & QUOTA BURNER TRACKERS 🔥
    private val sessionHandler = Handler(Looper.getMainLooper())
    private val allowedUntilMap = HashMap<String, Long>()
    private val sessionStartMap = HashMap<String, Long>()
    private val launchCooldownMap = HashMap<String, Long>()
    private val allocatedMinutesMap = HashMap<String, Int>()
    private var activePackageName: String? = null

    override fun onCreate() {
        overlayManager = OverlayManager(this)
        reminderManager = ReminderManager(overlayManager, ::onNewAppLaunch)
        restrictionManager = RestrictionManager(this, ::stopIfNoUsage)
        launchTrackingManager = LaunchTrackingManager(
            context = this,
            onNewAppLaunched = ::onNewAppLaunch,
            dismissOverlay = { overlayManager.dismissSheetOverlay() },
            cancelReminders = { reminderManager.cancelReminders() },
        )
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ServiceBinder.ACTION_START_MINDFUL_SERVICE) {
            startFgService()
            return START_STICKY
        }

        stopIfNoUsage()
        return START_NOT_STICKY
    }

    private fun startFgService() {
        try {
            val notification = NotificationHelper.buildFgServiceNotification(
                this,
                getString(R.string.app_blocker_running_notification_info)
            )
            startForeground(AppConstants.TRACKER_SERVICE_NOTIFICATION_ID, notification)
            Log.d(TAG, "startFgService: TRACKER service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "startFgService: Failed to start TRACKER service", e)
            SharedPrefsHelper.insertCrashLogToPrefs(this, e)
            stopIfNoUsage()
        }
    }

    fun onMidnightReset() {
        cleanupAllSessions()
        restrictionManager.resetCache()
        overlayManager.dismissSheetOverlay()
        val reminderAwaiting = reminderManager.cancelReminders()

        if (reminderAwaiting) launchTrackingManager.reInvokeLastLaunchEvent()
    }

    /**
     * Agar user app chhod kar chala jaye, toh unused minutes quota me wapas adjust karo
     */
    private fun settleUnusedQuota(pkg: String) {
        launchCooldownMap[pkg] = System.currentTimeMillis()
        val startTime = sessionStartMap.remove(pkg) ?: return
        val allocatedMins = allocatedMinutesMap.remove(pkg) ?: return
        allowedUntilMap.remove(pkg)

        val usedMillis = System.currentTimeMillis() - startTime
        val usedMinutes = kotlin.math.ceil(usedMillis / 60000.0).toInt()
        val unusedMinutes = allocatedMins - usedMinutes

        if (unusedMinutes > 0) {
            Log.d(TAG, "settleUnusedQuota: Refunding $unusedMinutes mins to $pkg (Used $usedMinutes of $allocatedMins mins)")
            // Restriction cache refresh taaki naya accurate balance reflect ho
            restrictionManager.resetCache()
        }
    }
    private fun getCooldownRemainingSeconds(pkg: String): Int {
        val lastExit = launchCooldownMap[pkg] ?: return 0
        val elapsed = System.currentTimeMillis() - lastExit
        // Agar exit ke baad 5 minute (300,000 ms) se kam hua hai, toh 10 second friction penalty
        return if (elapsed < 5 * 60 * 1000L) {
            10
        } else {
            0
        }
    }

    private fun cleanupAllSessions() {
        sessionHandler.removeCallbacksAndMessages(null)
        allowedUntilMap.clear()
        sessionStartMap.clear()
        allocatedMinutesMap.clear()
        activePackageName = null
    }

    @WorkerThread
    private fun onNewAppLaunch(packageName: String) {
        try {
            reminderManager.cancelReminders()

            // Agar user pichla app chhod kar kisi naye app me aaya hai, quota settle karo
            if (activePackageName != null && activePackageName != packageName) {
                settleUnusedQuota(activePackageName!!)
                sessionHandler.removeCallbacksAndMessages(null)
            }
            activePackageName = packageName

            val allowedUntil = allowedUntilMap[packageName] ?: 0L
            val isSessionActive = System.currentTimeMillis() < allowedUntil

            /// Check current restrictions
            val currentOrFutureState = restrictionManager.isAppRestricted(packageName)
            Log.d(TAG, "onNewAppLaunch: $packageName's evaluated state => $currentOrFutureState")

            currentOrFutureState?.let { state ->
                /// 1. HARD BLOCK: Bedtime, Focus Mode ya Quota pura 0 ho gaya
                if (state.type == RestrictionType.BEDTIME ||
                    state.type == RestrictionType.FOCUS ||
                    state.timeLeftMillis <= 0L
                ) {
                    overlayManager.showSheetOverlay(
                        packageName = packageName,
                        restrictionState = state,
                        addReminderWithDelay = null, // No timer buttons
                    )
                    return
                }

                /// 2. ACTIVE SESSION: Agar user ne pehle se select kiya hai aur time chal raha hai
                if (isSessionActive) {
                    return
                }

                /// 3. GATEKEEPER PROMPT: Har launch pe overlay dikhao aur session maango
                val cooldownSec = getCooldownRemainingSeconds(packageName)

                overlayManager.dismissSheetOverlay()
                overlayManager.showSheetOverlay(
                    packageName = packageName,
                    restrictionState = state,
                    cooldownSeconds = cooldownSec,
                    addReminderWithDelay = { futureMinutes ->
                        val now = System.currentTimeMillis()
                        val sessionDurationMs = futureMinutes * 60 * 1000L

                        sessionStartMap[packageName] = now
                        allocatedMinutesMap[packageName] = futureMinutes
                        allowedUntilMap[packageName] = now + sessionDurationMs

                        // 🔥 1-MINUTE WARNING NUDGE: Agar session 2 minute se bada hai, toh theek 1 minute pehle toast dikhao
                        if (futureMinutes > 1) {
                            val warningDelayMs = sessionDurationMs - (60 * 1000L)
                            sessionHandler.postDelayed({
                                if (allowedUntilMap.containsKey(packageName)) {
                                    android.widget.Toast.makeText(
                                        this@MindfulTrackerService,
                                        "⚠️ 1 minute bacha hai, wrap up kar!",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }, warningDelayMs)
                        }

                        // Timer pura hote hi wapas overlay trigger hoga
                        sessionHandler.postDelayed({
                            allowedUntilMap.remove(packageName)
                            sessionStartMap.remove(packageName)
                            allocatedMinutesMap.remove(packageName)
                            onNewAppLaunch(packageName)
                        }, sessionDurationMs)
                    },
                )
            } ?: run {
                overlayManager.dismissSheetOverlay()
            }
        } catch (e: Exception) {
            SharedPrefsHelper.insertCrashLogToPrefs(this, e)
            Log.e(TAG, "onNewAppLaunch: Failed to process new app launch event", e)
        }
    }

    private fun stopIfNoUsage() {
        if (restrictionManager.isIdle) {
            Log.d(TAG, "Service no longer needed, stopping")
            launchTrackingManager.dispose()
            reminderManager.cancelReminders()
            overlayManager.dismissSheetOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        cleanupAllSessions()
        Log.d(TAG, "onDestroy: TRACKER service destroyed successfully")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return if (intent.action == ServiceBinder.ACTION_BIND_TO_MINDFUL) mBinder else null
    }
}
