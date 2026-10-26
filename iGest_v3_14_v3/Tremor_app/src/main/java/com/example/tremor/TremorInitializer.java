// TremorInitializer.java
package com.example.tremor; // adjust to your tremor namespace

import android.content.Context;

public final class TremorInitializer {
    private TremorInitializer() {}
    public static void init(Context appContext) {
        // move library init logic here (DB, singletons, logging, etc.)

        // Schedule the hourly GitHub backup for the Tremor module.
        // WorkManager persists this across restarts; the KEEP policy in
        // BackupScheduler ensures calling init() repeatedly is safe.
        BackupScheduler.scheduleHourlyBackup(appContext);
    }
}