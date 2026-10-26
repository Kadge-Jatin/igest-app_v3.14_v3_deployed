package com.example.tremor;

/**
 * BackupConfig — pilot-only configuration for the GitHub backup feature.
 *
 * INSTRUCTIONS (fill in before first use):
 *  1. Create a private GitHub repo (e.g. "igest-tremor-backup") under your account.
 *  2. Generate a Personal Access Token:
 *       GitHub → Settings → Developer Settings → Tokens (classic) → "repo" scope.
 *  3. Paste the token into GITHUB_TOKEN below.
 *  4. Set REPO_OWNER to your GitHub username and REPO_NAME to your backup repo name.
 *
 * WARNING: This token grants write access to the backup repo.
 *          This file must NEVER be pushed to a public repository.
 *          For production, replace with encrypted/keystore-based secret storage.
 */
public final class BackupConfig {

    private BackupConfig() {}

    /** Your GitHub Personal Access Token (classic, "repo" scope). */
    public static final String GITHUB_TOKEN = "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";

    /** GitHub username or organisation that owns the backup repo. */
    public static final String REPO_OWNER = "Kadge-Jatin";

    /** Name of the private backup repository (must already exist and be empty). */
    public static final String REPO_NAME = "igest-tremor_data-backup_30jun";

    /** SharedPreferences file used to track which files have already been uploaded. */
    static final String PREFS_BACKUP = "tremor_github_backup";

    /** SharedPreferences key for the uploaded-file path set. */
    static final String PREF_UPLOADED_PATHS = "uploaded_paths";

    /** Root folder name used inside the backup repo to mirror the on-device folder. */
    static final String BACKUP_ROOT_IN_REPO = "iGest_Tremor_Backup";

    /**
     * Returns true when the config has been filled in (token is not the placeholder).
     * GitHubBackupWorker checks this and skips the run silently if false,
     * so the app does not crash when the token is not yet configured.
     */
    public static boolean isConfigured() {
        return !GITHUB_TOKEN.equals("YOUR_GITHUB_TOKEN_HERE")
                && !REPO_OWNER.equals("YOUR_GITHUB_USERNAME_HERE");
    }
}