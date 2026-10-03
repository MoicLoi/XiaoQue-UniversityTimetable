package com.xiqueer.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xiqueer.android.XqRepository
import com.xiqueer.android.notify.ReminderScheduler
import java.util.concurrent.TimeUnit

/**
 * 后台刷新课表并重排提醒。
 *
 * 每 6 小时一次:接口只回**当前周**的课表,跨周时必须刷新才能拿到新一周
 * (`zc`/`qssj` 会随之前移),否则下周的提醒会全部落空。
 */
class DailyRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = XqRepository(applicationContext)
        if (!repo.hasSession) return Result.success()
        repo.restore()
        return runCatching {
            repo.refreshTimetable(null)
            ReminderScheduler.reschedule(applicationContext)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val NAME = "xq-daily-refresh"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
