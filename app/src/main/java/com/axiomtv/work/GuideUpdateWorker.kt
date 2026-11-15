package com.axiomtv.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.axiomtv.data.AxiomRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Keeps the TV guide fresh in the background, even when the app is closed. */
class GuideUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun repository(): AxiomRepository
    }

    override suspend fun doWork(): Result =
        try {
            val repository = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).repository()
            val updated = repository.syncGuideIfDue()
            Timber.i("Background guide check: %s", if (updated) "updated" else "not due")
            Result.success()
        } catch (ex: Exception) {
            Timber.w(ex, "Background guide update failed")
            Result.retry()
        }

    companion object {
        private const val NAME = "guide-update"

        /** Every 12 hours, first run around 4am, only with a network connection. */
        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val next =
                (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 4)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
                }
            val request =
                PeriodicWorkRequestBuilder<GuideUpdateWorker>(12, TimeUnit.HOURS)
                    .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }.onFailure { Timber.w(it, "Couldn't schedule guide updates") }
        }
    }
}
