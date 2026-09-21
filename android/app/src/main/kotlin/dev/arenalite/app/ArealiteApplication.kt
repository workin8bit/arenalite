package dev.arenalite.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.arenalite.app.data.AndroidFileSystem
import dev.arenalite.app.data.AppDatabase
import dev.arenalite.app.data.SettingsStore
import dev.arenalite.app.data.WorkspaceRepository
import dev.arenalite.app.sync.SyncWorker
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Composition root. Everything the core needs from the platform (file system,
 * HTTP, storage) is constructed once here and handed to the repository.
 */
class ArealiteApplication : Application() {

    lateinit var repository: WorkspaceRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val workspaceRoot = File(filesDir, "workspaces").apply { mkdirs() }
        val fileSystem = AndroidFileSystem(workspaceRoot)
        val database = AppDatabase.get(this)
        val settings = SettingsStore(this)

        repository = WorkspaceRepository(
            context = this,
            fileSystem = fileSystem,
            database = database,
            settings = settings,
        )

        scheduleBackgroundSync()
    }

    /**
     * Sync runs every 6 hours and on every app start. There is no cap on how
     * much it uploads — a workspace keeps syncing for as long as it exists.
     */
    private fun scheduleBackgroundSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "arenalite-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        lateinit var instance: ArealiteApplication
            private set
    }
}
