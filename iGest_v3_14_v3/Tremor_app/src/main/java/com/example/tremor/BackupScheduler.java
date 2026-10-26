package com.example.tremor;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * BackupScheduler — schedules the hourly GitHub backup using WorkManager.
 *
 * Call {@link #scheduleHourlyBackup(Context)} once at app startup (e.g. from
 * {@link TremorInitializer#init(Context)}). WorkManager persists the schedule
 * across app restarts and device reboots automatically.
 *
 * The work is tagged with {@link #WORK_NAME}; using
 * {@code ExistingPeriodicWorkPolicy.KEEP} means repeated calls at startup are safe
 * — the existing schedule is kept and no duplicate workers are created.
 */
public final class BackupScheduler {

    private static final String TAG = "BackupScheduler";

    /** Unique name for the periodic work so duplicates are avoided. */
    public static final String WORK_NAME = "tremor_github_hourly_backup";

    private BackupScheduler() {}

    /**
     * Schedules the hourly GitHub backup worker.
     *
     * <p>Constraints: network must be connected before the worker runs.
     * <p>Policy: {@code KEEP} — if a job already exists, leave it untouched.
     *
     * @param context any valid application context
     */
    public static void scheduleHourlyBackup(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest backupRequest =
                new PeriodicWorkRequest.Builder(GitHubBackupWorker.class, 3, TimeUnit.HOURS)
//                new PeriodicWorkRequest.Builder(GitHubBackupWorker.class, 15, TimeUnit.MINUTES)
                        .setConstraints(constraints)

                        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
//                ExistingPeriodicWorkPolicy.REPLACE,  // ← change KEEP to REPLACE during debug
                backupRequest);

        Log.i(TAG, "Hourly GitHub backup scheduled (KEEP policy).");
    }
}