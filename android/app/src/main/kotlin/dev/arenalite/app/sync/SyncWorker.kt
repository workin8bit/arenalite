package dev.arenalite.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.arenalite.app.ArealiteApplication

/**
 * Periodic background sync.
 *
 * Every workspace is pushed, every time, with no size guard: the whole point of
 * the product is that a workspace can grow without bound and still be mirrored
 * to cloud storage.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = (applicationContext as? ArealiteApplication)?.repository ?: return Result.failure()
        return try {
            val sessions = repository.engine.listSessions()
            for (session in sessions) {
                repository.syncPush(session.id)
            }
            Result.success()
        } catch (e: Exception) {
            // Transient network errors should retry, not fail permanently.
            Result.retry()
        }
    }
}
