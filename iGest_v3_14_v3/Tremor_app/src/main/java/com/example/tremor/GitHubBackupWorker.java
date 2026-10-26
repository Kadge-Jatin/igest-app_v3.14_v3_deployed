package com.example.tremor;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * GitHubBackupWorker — WorkManager Worker that backs up closed tremor data files
 * to a private GitHub repository.
 *
 * Design principles:
 *  - Runs only when internet is available (WorkManager NetworkType.CONNECTED constraint).
 *  - Uploads only files that have NOT been uploaded before (tracked in SharedPreferences).
 *  - Preserves the exact local folder hierarchy inside the GitHub repo.
 *  - Skips any file that is currently open for writing (active CsvWriter session).
 *  - Skips any file modified in the last 60 seconds (extra safety margin).
 *  - Never deletes local files.
 *  - Never overwrites an existing GitHub file (treats 422 as "already uploaded").
 *  - On any per-file failure: logs, skips that file, retries on the next hourly cycle.
 *  - If BackupConfig is not yet filled in, exits silently without error.
 */
public class GitHubBackupWorker extends Worker {

    private static final String TAG = "GitHubBackupWorker";

    /** Files modified within this window are considered potentially still-open. */
    private static final long RECENT_WRITE_THRESHOLD_MS = 60_000L;

    /** GitHub Contents API base URL. */
    private static final String GITHUB_API_BASE = "https://api.github.com";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient();

    public GitHubBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // 1. Skip if config placeholder is still present
        if (!BackupConfig.isConfigured()) {
            Log.i(TAG, "BackupConfig not yet filled in — skipping backup run.");
            return Result.success();
        }

        // 2. Double-check connectivity (WorkManager constraint already ensures this,
        //    but we verify in case the constraint is not enforced on older APIs)
        if (!isNetworkAvailable()) {
            Log.i(TAG, "No network — skipping backup run.");
            return Result.success();
        }

        Context ctx = getApplicationContext();

        // 3. Locate the Tremor data root on external storage
        File externalFilesDir = ctx.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            Log.w(TAG, "External files dir unavailable — skipping backup run.");
            return Result.success();
        }
        File tremorRoot = new File(externalFilesDir, "iGest: Tremor Detection");
        if (!tremorRoot.exists()) {
            Log.i(TAG, "Tremor root does not exist yet — nothing to back up.");
            return Result.success();
        }

        // 4. Load set of already-uploaded relative paths
        SharedPreferences prefs = ctx.getSharedPreferences(BackupConfig.PREFS_BACKUP, Context.MODE_PRIVATE);
        Set<String> uploadedPaths = new HashSet<>(
                prefs.getStringSet(BackupConfig.PREF_UPLOADED_PATHS, new HashSet<>()));

        // 5. Gather absolute path of the currently-open CSV (if any) to skip it
        String activeCsvPath = MainActivity.getActiveCsvAbsPath();

        // 6. Recursively collect all candidate files
        Set<String> newUploads = new HashSet<>();
        walkAndUpload(tremorRoot, tremorRoot, uploadedPaths, newUploads, activeCsvPath);

        // 7. Persist updated uploaded-path set
        if (!newUploads.isEmpty()) {
            uploadedPaths.addAll(newUploads);
            prefs.edit()
                    .putStringSet(BackupConfig.PREF_UPLOADED_PATHS, uploadedPaths)
                    .apply();
            Log.i(TAG, "Backup run complete. Newly uploaded files: " + newUploads.size());
        } else {
            Log.i(TAG, "Backup run complete. No new files to upload.");
        }

        return Result.success();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Recursive directory walker
    // ──────────────────────────────────────────────────────────────────────────

    private void walkAndUpload(
            File tremorRoot,
            File current,
            Set<String> uploadedPaths,
            Set<String> newUploads,
            String activeCsvPath) {

        File[] entries = current.listFiles();
        if (entries == null) return;

        for (File entry : entries) {
            if (entry.isDirectory()) {
                walkAndUpload(tremorRoot, entry, uploadedPaths, newUploads, activeCsvPath);
            } else if (entry.isFile()) {
                uploadFileIfEligible(tremorRoot, entry, uploadedPaths, newUploads, activeCsvPath);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Per-file eligibility check and upload
    // ──────────────────────────────────────────────────────────────────────────

    private void uploadFileIfEligible(
            File tremorRoot,
            File file,
            Set<String> uploadedPaths,
            Set<String> newUploads,
            String activeCsvPath) {

        String absPath = file.getAbsolutePath();

        // Skip if this is the currently-open raw CSV
        if (activeCsvPath != null && activeCsvPath.equals(absPath)) {
            Log.d(TAG, "Skipping open CSV: " + file.getName());
            return;
        }

        // Skip .tmp files (analysed CSV is written via a .tmp then renamed; skip
        // the .tmp just in case it is still present)
        if (file.getName().endsWith(".tmp")) {
            Log.d(TAG, "Skipping .tmp file: " + file.getName());
            return;
        }

        // Skip files modified very recently (extra safety for edge cases)
        long ageMs = System.currentTimeMillis() - file.lastModified();
        if (ageMs < RECENT_WRITE_THRESHOLD_MS) {
            Log.d(TAG, "Skipping recently-modified file (" + ageMs + "ms): " + file.getName());
            return;
        }

        // Compute relative path from tremorRoot (used as the key and GitHub path)
        String relPath = getRelativePath(tremorRoot, file);
        if (relPath == null) {
            Log.w(TAG, "Could not compute relative path for: " + absPath);
            return;
        }

        // Skip if already uploaded
        if (uploadedPaths.contains(relPath)) {
            return;
        }

        // Attempt upload
        boolean uploaded = uploadToGitHub(file, relPath);
        if (uploaded) {
            newUploads.add(relPath);
        }
        // On failure: do nothing — file will be retried in the next hourly cycle
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GitHub REST API upload (PUT /repos/:owner/:repo/contents/:path)
    // ──────────────────────────────────────────────────────────────────────────

    private boolean uploadToGitHub(File file, String relPath) {
        try {
            byte[] fileBytes = readFileBytes(file);
            if (fileBytes == null) {
                Log.w(TAG, "Could not read file: " + file.getName());
                return false;
            }

            String base64Content = Base64.encodeToString(fileBytes, Base64.NO_WRAP);

            // GitHub path: BACKUP_ROOT_IN_REPO/relPath
            // Replace backslashes (Windows paths) just in case
            String githubPath = BackupConfig.BACKUP_ROOT_IN_REPO + "/"
                    + relPath.replace('\\', '/');

            String url = GITHUB_API_BASE + "/repos/"
                    + BackupConfig.REPO_OWNER + "/"
                    + BackupConfig.REPO_NAME + "/contents/"
                    + githubPath;

            JSONObject body = new JSONObject();
            body.put("message", "Backup: " + relPath.replace('\\', '/'));
            body.put("content", base64Content);

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + BackupConfig.GITHUB_TOKEN)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .put(RequestBody.create(body.toString(), JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                int code = response.code();
                if (code == 201) {
                    Log.i(TAG, "Uploaded: " + relPath);
                    return true;
                } else if (code == 422) {
                    // 422 Unprocessable Entity = file already exists on GitHub
                    Log.i(TAG, "Already exists on GitHub (treating as uploaded): " + relPath);
                    return true;
                } else {
                    Log.w(TAG, "Upload failed for " + relPath + " — HTTP " + code);
                    return false;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception uploading " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Returns the path of {@code file} relative to {@code root}, or null on error. */
    private static String getRelativePath(File root, File file) {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (filePath.startsWith(rootPath)) {
                String rel = filePath.substring(rootPath.length());
                // Strip leading separator
                if (rel.startsWith(File.separator)) rel = rel.substring(File.separator.length());
                return rel;
            }
        } catch (IOException e) {
            Log.w(TAG, "getRelativePath error: " + e.getMessage());
        }
        return null;
    }

    /** Reads all bytes from a file, returning null if an error occurs. */
    private static byte[] readFileBytes(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream((int) file.length())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            Log.w(TAG, "readFileBytes error for " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /** Checks whether an active network connection is available. */
    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager)
                getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            // Fallback for API < 23 (minSdk of Tremor_app is 21)
            @SuppressWarnings("deprecation")
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }
}