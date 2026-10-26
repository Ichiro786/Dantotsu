package ani.dantotsu.notifications

import android.content.Context
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.subscription.SubscriptionNotificationWorker
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

interface TaskScheduler {
    fun scheduleRepeatingTask(taskType: TaskType, interval: Long)
    fun cancelTask(taskType: TaskType)

    fun cancelAllTasks() {
        for (taskType in TaskType.entries) {
            cancelTask(taskType)
        }
    }

    /**
     * Called on every process start. Unlike [scheduleAllTasks] this must not push back
     * tasks that are already scheduled.
     */
    fun ensureAllTasksScheduled(context: Context) = scheduleAllTasks(context)

    fun scheduleAllTasks(context: Context) {
        for (taskType in TaskType.entries) {
            val interval = when (taskType) {
                TaskType.COMMENT_NOTIFICATION -> CommentNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.CommentNotificationInterval
                )]

                TaskType.ANILIST_NOTIFICATION -> AnilistNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.AnilistNotificationInterval
                )]

                TaskType.SUBSCRIPTION_NOTIFICATION -> SubscriptionNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.SubscriptionNotificationInterval
                )]
            }
            scheduleRepeatingTask(taskType, interval)
        }
    }

    companion object {
        fun create(context: Context, useAlarmManager: Boolean): TaskScheduler {
            return if (useAlarmManager) {
                AlarmManagerScheduler(context)
            } else {
                WorkManagerScheduler(context)
            }
        }

        /**
         * One-off notification checks when the app is opened. KEEP + an initial delay + a
         * network constraint so they don't compete with the Home load and aren't re-queued
         * (and restarted) by every activity recreation.
         */
        fun scheduleSingleWork(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            fun enqueue(name: String, worker: Class<out androidx.work.ListenableWorker>) {
                workManager.enqueueUniqueWork(
                    name + "_single",
                    androidx.work.ExistingWorkPolicy.KEEP,
                    androidx.work.OneTimeWorkRequest.Builder(worker)
                        .setConstraints(constraints)
                        .setInitialDelay(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                )
            }
            enqueue(CommentNotificationWorker.WORK_NAME, CommentNotificationWorker::class.java)
            enqueue(AnilistNotificationWorker.WORK_NAME, AnilistNotificationWorker::class.java)
            enqueue(SubscriptionNotificationWorker.WORK_NAME, SubscriptionNotificationWorker::class.java)
        }
    }

    enum class TaskType {
        COMMENT_NOTIFICATION,
        ANILIST_NOTIFICATION,
        SUBSCRIPTION_NOTIFICATION
    }
}

interface Task {
    suspend fun execute(context: Context): Boolean
}
