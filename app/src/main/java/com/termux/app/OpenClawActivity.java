package com.termux.app;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.shell.TermuxShellManager;

import java.io.File;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Main launcher activity for OpenClaw.
 *
 * Shows a WebView pointing at the OpenClaw Control Panel (localhost:18789) once the gateway
 * is up.  On first launch it shows an onboarding screen to collect the user's API key,
 * then kicks off Termux bootstrap + OpenClaw install in the background.
 *
 * All commands are executed through {@link TermuxService#createTermuxTask} which properly
 * sets up the Termux shell environment (PATH, PREFIX, HOME, dynamic linker paths, etc.).
 * This is critical because Termux's Node.js, git, and other binaries require the correct
 * LD paths and Android-specific linker setup that raw ProcessBuilder cannot provide.
 *
 * The terminal (TermuxActivity) is accessible via a floating action button.
 */
public final class OpenClawActivity extends AppCompatActivity implements ServiceConnection {

    private static final String LOG_TAG = "OpenClawActivity";
    private static final String PREFS_NAME = "openclaw_prefs";
    private static final String PREF_ONBOARDED = "onboarded";
    private static final String PREF_API_KEY = "api_key";
    private static final String PREF_GATEWAY_TOKEN = "gateway_token";
    private static final int GATEWAY_PORT = 18789;
    private static final String GATEWAY_URL = "http://127.0.0.1:" + GATEWAY_PORT;

    /** Interval (ms) between gateway health checks. */
    private static final long POLL_INTERVAL_MS = 2000;
    /** Interval (ms) between install completion checks. */
    private static final long INSTALL_CHECK_INTERVAL_MS = 1500;

    private WebView mWebView;
    private View mStatusOverlay;
    private View mOnboarding;
    private ProgressBar mProgressBar;
    private TextView mStatusText;
    private FloatingActionButton mTerminalFab;

    private TermuxService mTermuxService;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mGatewayReady = false;
    private boolean mInstallStarted = false;

    /** Track the currently running install task so we can poll for completion. */
    private AppShell mInstallTask;
    /** Track the gateway task. */
    private AppShell mGatewayTask;

    // ──────────────────────────────────────────────
    //  Gateway readiness poller
    // ──────────────────────────────────────────────

    private final Runnable mGatewayPoller = new Runnable() {
        @Override
        public void run() {
            if (mGatewayReady) return;
            new Thread(() -> {
                boolean reachable = isGatewayReachable();
                mHandler.post(() -> {
                    if (reachable && !mGatewayReady) {
                        onGatewayReady();
                    } else if (!reachable) {
                        mHandler.postDelayed(mGatewayPoller, POLL_INTERVAL_MS);
                    }
                });
            }).start();
        }
    };

    // ──────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_openclaw);

        mWebView = findViewById(R.id.openclaw_webview);
        mStatusOverlay = findViewById(R.id.openclaw_status_overlay);
        mOnboarding = findViewById(R.id.openclaw_onboarding);
        mProgressBar = findViewById(R.id.openclaw_progress);
        mStatusText = findViewById(R.id.openclaw_status_text);
        mTerminalFab = findViewById(R.id.openclaw_terminal_fab);

        setupWebView();
        setupTerminalFab();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean onboarded = prefs.getBoolean(PREF_ONBOARDED, false);

        if (!onboarded) {
            showOnboarding();
        } else {
            showStatusOverlay("Starting OpenClaw…", 10);
            startTermuxServiceAndInstall();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        try {
            unbindService(this);
        } catch (Exception ignored) {
        }
    }

    // ──────────────────────────────────────────────
    //  Onboarding
    // ──────────────────────────────────────────────

    private void showOnboarding() {
        mStatusOverlay.setVisibility(View.GONE);
        mOnboarding.setVisibility(View.VISIBLE);
        mWebView.setVisibility(View.GONE);

        TextInputEditText apiKeyInput = findViewById(R.id.openclaw_api_key_input);
        TextInputEditText gatewayTokenInput = findViewById(R.id.openclaw_gateway_token_input);
        MaterialButton startButton = findViewById(R.id.openclaw_start_button);
        TextView skipButton = findViewById(R.id.openclaw_skip_button);

        startButton.setOnClickListener(v -> {
            String apiKey = apiKeyInput.getText() != null ? apiKeyInput.getText().toString().trim() : "";
            String gatewayToken = gatewayTokenInput.getText() != null ? gatewayTokenInput.getText().toString().trim() : "";

            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Please enter an API key", Toast.LENGTH_SHORT).show();
                return;
            }

            saveCredentials(apiKey, gatewayToken);
            finishOnboarding();
        });

        skipButton.setOnClickListener(v -> {
            saveCredentials("", "");
            finishOnboarding();
        });
    }

    private void saveCredentials(String apiKey, String gatewayToken) {
        if (gatewayToken.isEmpty()) {
            gatewayToken = UUID.randomUUID().toString();
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putBoolean(PREF_ONBOARDED, true)
            .putString(PREF_API_KEY, apiKey)
            .putString(PREF_GATEWAY_TOKEN, gatewayToken)
            .apply();
    }

    private void finishOnboarding() {
        mOnboarding.setVisibility(View.GONE);
        showStatusOverlay("Setting up OpenClaw…", 5);
        startTermuxServiceAndInstall();
    }

    // ──────────────────────────────────────────────
    //  Termux Service Binding
    // ──────────────────────────────────────────────

    private void startTermuxServiceAndInstall() {
        try {
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);
            bindService(serviceIntent, this, 0);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start TermuxService", e);
            showStatusOverlay("Failed to start background service", 0);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");
        mTermuxService = ((TermuxService.LocalBinder) service).service;

        showStatusOverlay("Installing base system…", 15);

        // Bootstrap sets up the Termux filesystem ($PREFIX/bin, etc.)
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            if (mTermuxService == null) return;
            showStatusOverlay("Base system ready. Installing OpenClaw…", 30);
            startOpenClawInstall();
        });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");
        mTermuxService = null;
    }

    // ──────────────────────────────────────────────
    //  OpenClaw Installation via TermuxService tasks
    // ──────────────────────────────────────────────

    private void startOpenClawInstall() {
        if (mInstallStarted) return;
        mInstallStarted = true;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String apiKey = prefs.getString(PREF_API_KEY, "");
        String gatewayToken = prefs.getString(PREF_GATEWAY_TOKEN, UUID.randomUUID().toString());

        // Check if gateway is already running (e.g. app was killed and restarted)
        new Thread(() -> {
            if (isGatewayReachable()) {
                mHandler.post(this::onGatewayReady);
                return;
            }
            mHandler.post(() -> runInstallPipeline(apiKey, gatewayToken));
        }).start();
    }

    /**
     * Write a self-contained install script and run it as a single Termux background task.
     * Using a script avoids callback chaining and ensures all steps run in sequence within
     * the proper Termux environment (correct PATH, linker, locale, etc.).
     */
    private void runInstallPipeline(String apiKey, String gatewayToken) {
        if (mTermuxService == null) {
            showStatusOverlay("Service disconnected — please restart the app", 0);
            return;
        }

        // The HOME dir inside Termux is /data/data/com.termux/files/home
        String homeDir = getFilesDir().getAbsolutePath() + "/home";
        String openclawDir = homeDir + "/openclaw";
        String envFile = openclawDir + "/.env";
        String scriptPath = homeDir + "/.openclaw-install.sh";

        // Build the install script
        StringBuilder sb = new StringBuilder();
        sb.append("#!/data/data/com.termux/files/usr/bin/bash\n");
        sb.append("set -e\n");
        sb.append("export TERM=dumb\n\n");

        // Create openclaw dir and .env file
        sb.append("mkdir -p '").append(openclawDir).append("'\n");
        sb.append("cat > '").append(envFile).append("' << 'ENVEOF'\n");
        sb.append("OPENAI_API_KEY=").append(apiKey).append("\n");
        sb.append("OPENCLAW_GATEWAY_TOKEN=").append(gatewayToken).append("\n");
        sb.append("NODE_OPTIONS=--max-old-space-size=512\n");
        sb.append("ENVEOF\n\n");

        // Check if already installed
        sb.append("if [ -f '").append(openclawDir).append("/openclaw.mjs' ]; then\n");
        sb.append("  echo 'OPENCLAW_ALREADY_INSTALLED'\n");
        sb.append("  exit 0\n");
        sb.append("fi\n\n");

        // Install Node.js LTS if not present
        sb.append("if ! command -v node > /dev/null 2>&1; then\n");
        sb.append("  echo 'OPENCLAW_STEP: Installing Node.js…'\n");
        sb.append("  pkg install -y nodejs-lts\n");
        sb.append("fi\n\n");

        // Install git if not present
        sb.append("if ! command -v git > /dev/null 2>&1; then\n");
        sb.append("  echo 'OPENCLAW_STEP: Installing git…'\n");
        sb.append("  pkg install -y git\n");
        sb.append("fi\n\n");

        // Install pnpm globally
        sb.append("echo 'OPENCLAW_STEP: Installing pnpm…'\n");
        sb.append("npm install -g pnpm 2>&1 || true\n\n");

        // Install pm2 globally
        sb.append("echo 'OPENCLAW_STEP: Installing pm2…'\n");
        sb.append("npm install -g pm2 2>&1 || true\n\n");

        // Clone openclaw-termux
        sb.append("if [ ! -d '").append(openclawDir).append("/.git' ]; then\n");
        sb.append("  echo 'OPENCLAW_STEP: Downloading OpenClaw…'\n");
        sb.append("  rm -rf '").append(openclawDir).append("'\n");
        sb.append("  git clone --depth 1 https://github.com/yunze7373/openclaw-termux.git '").append(openclawDir).append("'\n");
        sb.append("fi\n\n");

        // Re-write .env after clone (clone may have overwritten the directory)
        sb.append("cat > '").append(envFile).append("' << 'ENVEOF'\n");
        sb.append("OPENAI_API_KEY=").append(apiKey).append("\n");
        sb.append("OPENCLAW_GATEWAY_TOKEN=").append(gatewayToken).append("\n");
        sb.append("NODE_OPTIONS=--max-old-space-size=512\n");
        sb.append("ENVEOF\n\n");

        // Install dependencies
        sb.append("echo 'OPENCLAW_STEP: Installing dependencies…'\n");
        sb.append("cd '").append(openclawDir).append("'\n");
        sb.append("pnpm install --no-frozen-lockfile 2>&1\n\n");

        sb.append("echo 'OPENCLAW_INSTALL_COMPLETE'\n");

        // Write script to disk (we write from Java since Termux may not be bootstrapped yet
        // when this is called the first time — but by the time we get here, bootstrap is done)
        try {
            File scriptFile = new File(scriptPath);
            scriptFile.getParentFile().mkdirs();
            FileWriter writer = new FileWriter(scriptFile);
            writer.write(sb.toString());
            writer.close();
            scriptFile.setExecutable(true);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write install script", e);
            showStatusOverlay("Failed to write install script: " + e.getMessage(), 0);
            return;
        }

        // Execute via TermuxService.createTermuxTask() — this uses the proper Termux
        // shell environment (PATH, PREFIX, HOME, dynamic linker, etc.)
        String shell = getFilesDir().getAbsolutePath() + "/usr/bin/bash";
        ExecutionCommand cmd = new ExecutionCommand(
            TermuxShellManager.getNextShellId(),
            shell,                                      // executable
            new String[]{scriptPath},                    // arguments
            null,                                        // stdin
            homeDir,                                     // working directory
            ExecutionCommand.Runner.APP_SHELL.getName(), // runner type
            false                                        // isFailsafe
        );
        cmd.shellName = "openclaw-install";

        mInstallTask = mTermuxService.createTermuxTask(cmd);
        if (mInstallTask == null) {
            Logger.logError(LOG_TAG, "Failed to create install task");
            showStatusOverlay("Failed to start installer — try reopening the app", 0);
            return;
        }

        Logger.logDebug(LOG_TAG, "Install task started, polling for completion…");
        updateStatus("Installing OpenClaw…", 40);
        mHandler.postDelayed(mInstallChecker, INSTALL_CHECK_INTERVAL_MS);
    }

    /**
     * Poll the install task for completion. When done, start the gateway.
     */
    private final Runnable mInstallChecker = new Runnable() {
        @Override
        public void run() {
            if (mInstallTask == null) return;

            ExecutionCommand ec = mInstallTask.getExecutionCommand();
            if (ec.hasExecuted() || ec.isStateFailed()) {
                int exitCode = ec.resultData.exitCode != null ? ec.resultData.exitCode : -1;
                String stdout = ec.resultData.stdout != null ? ec.resultData.stdout.toString() : "";

                if (exitCode == 0) {
                    Logger.logDebug(LOG_TAG, "Install completed successfully");
                    updateStatus("Starting gateway…", 85);
                    startGateway();
                } else {
                    Logger.logError(LOG_TAG, "Install failed with exit code " + exitCode);
                    String lastLine = getLastMeaningfulLine(stdout);
                    updateStatus("Install failed (exit " + exitCode + "): " + lastLine, 0);
                }
            } else {
                // Still running — update progress based on output markers
                String stdout = ec.resultData.stdout != null ? ec.resultData.stdout.toString() : "";
                updateProgressFromOutput(stdout);
                mHandler.postDelayed(this, INSTALL_CHECK_INTERVAL_MS);
            }
        }
    };

    /**
     * Start the OpenClaw gateway via pm2 inside a Termux background task.
     */
    private void startGateway() {
        if (mTermuxService == null) {
            showStatusOverlay("Service disconnected", 0);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String apiKey = prefs.getString(PREF_API_KEY, "");
        String gatewayToken = prefs.getString(PREF_GATEWAY_TOKEN, "");

        String homeDir = getFilesDir().getAbsolutePath() + "/home";
        String openclawDir = homeDir + "/openclaw";
        String shell = getFilesDir().getAbsolutePath() + "/usr/bin/bash";

        // Build a concise gateway start command:
        // 1. Source the .env file for credentials
        // 2. Stop any existing pm2 instance of openclaw (idempotent)
        // 3. Start the gateway via pm2
        String startCmd =
            "cd '" + openclawDir + "' && " +
            "export $(grep -v '^#' .env | xargs) && " +
            "pm2 delete openclaw 2>/dev/null; " +
            "pm2 start openclaw.mjs --name openclaw -- gateway --bind lan --port " + GATEWAY_PORT;

        ExecutionCommand cmd = new ExecutionCommand(
            TermuxShellManager.getNextShellId(),
            shell,
            new String[]{"-c", startCmd},
            null,
            openclawDir,
            ExecutionCommand.Runner.APP_SHELL.getName(),
            false
        );
        cmd.shellName = "openclaw-gateway";

        mGatewayTask = mTermuxService.createTermuxTask(cmd);
        if (mGatewayTask == null) {
            Logger.logError(LOG_TAG, "Failed to create gateway task");
            showStatusOverlay("Failed to start gateway — open terminal to debug", 0);
            mTerminalFab.setVisibility(View.VISIBLE);
            return;
        }

        updateStatus("Waiting for gateway…", 90);
        // Start polling for gateway HTTP readiness
        mHandler.postDelayed(mGatewayPoller, POLL_INTERVAL_MS);
    }

    // ──────────────────────────────────────────────
    //  Gateway Readiness
    // ──────────────────────────────────────────────

    private boolean isGatewayReachable() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(GATEWAY_URL + "/v1/models").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500; // Gateway is alive (even 401 means it's running)
        } catch (Exception e) {
            return false;
        }
    }

    private void onGatewayReady() {
        mGatewayReady = true;
        mStatusOverlay.setVisibility(View.GONE);
        mWebView.setVisibility(View.VISIBLE);
        mTerminalFab.setVisibility(View.VISIBLE);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(PREF_GATEWAY_TOKEN, "");
        String url = GATEWAY_URL;
        if (!token.isEmpty()) {
            url += "/?token=" + token;
        }
        mWebView.loadUrl(url);
    }

    // ──────────────────────────────────────────────
    //  WebView Setup
    // ──────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mWebView.setVisibility(View.GONE);
                    showStatusOverlay("Connecting to gateway…", 90);
                    mGatewayReady = false;
                    mHandler.postDelayed(mGatewayPoller, POLL_INTERVAL_MS);
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // localhost — safe to proceed
                handler.proceed();
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient());
    }

    // ──────────────────────────────────────────────
    //  Terminal FAB
    // ──────────────────────────────────────────────

    private void setupTerminalFab() {
        mTerminalFab.setOnClickListener(v -> {
            Intent intent = new Intent(this, TermuxActivity.class);
            startActivity(intent);
        });
    }

    // ──────────────────────────────────────────────
    //  UI Helpers
    // ──────────────────────────────────────────────

    private void showStatusOverlay(String text, int progress) {
        mStatusOverlay.setVisibility(View.VISIBLE);
        mWebView.setVisibility(View.GONE);
        mOnboarding.setVisibility(View.GONE);
        mStatusText.setText(text);
        if (progress > 0) {
            mProgressBar.setIndeterminate(false);
            mProgressBar.setProgress(progress);
        } else {
            mProgressBar.setIndeterminate(true);
        }
    }

    private void updateStatus(String text, int progress) {
        mHandler.post(() -> {
            mStatusText.setText(text);
            if (progress > 0) {
                mProgressBar.setIndeterminate(false);
                mProgressBar.setProgress(progress);
            }
        });
    }

    /**
     * Update progress bar based on OPENCLAW_STEP markers in stdout.
     */
    private void updateProgressFromOutput(String stdout) {
        if (stdout.contains("OPENCLAW_ALREADY_INSTALLED")) {
            updateStatus("OpenClaw already installed. Starting gateway…", 80);
        } else if (stdout.contains("Installing dependencies")) {
            updateStatus("Installing dependencies…", 70);
        } else if (stdout.contains("Downloading OpenClaw")) {
            updateStatus("Downloading OpenClaw…", 60);
        } else if (stdout.contains("Installing pm2")) {
            updateStatus("Installing pm2…", 55);
        } else if (stdout.contains("Installing pnpm")) {
            updateStatus("Installing pnpm…", 50);
        } else if (stdout.contains("Installing git")) {
            updateStatus("Installing git…", 45);
        } else if (stdout.contains("Installing Node")) {
            updateStatus("Installing Node.js…", 40);
        }
    }

    /**
     * Extract the last non-empty line from stdout for error display.
     */
    private static String getLastMeaningfulLine(String text) {
        if (text == null || text.isEmpty()) return "(no output)";
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) return line;
        }
        return "(no output)";
    }

    @Override
    public void onBackPressed() {
        if (mWebView.getVisibility() == View.VISIBLE && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
