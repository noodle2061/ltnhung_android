package com.example.nhandientienghet;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler; // Import Handler
import android.os.Looper; // Import Looper
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton; // Import ImageButton
import android.widget.ProgressBar; // Import ProgressBar
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

// Bỏ import không cần thiết
// import com.google.android.gms.tasks.OnCompleteListener;
// import com.google.android.gms.tasks.Task;
// import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {

    private static final String TAG = "MainActivity";
    public static boolean isActivityVisible;

    // --- UI Components (Updated) ---
    private TextView tvGreeting;
    private TextView tvDeviceStatusLabel;
    private TextView tvDeviceStatus;
    private TextView tvLastNoiseTimeLabel;
    private TextView tvLastNoiseTime;
    private ImageButton btnMainPlayPause;
    private ProgressBar progressBarMainAudio;
    private Button btnViewChart;
    private Button btnViewHistory;

    // --- Broadcast Receivers and Filters ---
    private BroadcastReceiver playAudioReceiver; // Receiver for handling PLAY_AUDIO_NOW
    private IntentFilter playAudioFilter;
    // Token receiver is removed

    // --- MediaPlayer Components for Last Audio ---
    private MediaPlayer lastAudioMediaPlayer; // Player for the audio in the CardView
    private String lastReceivedAudioUrl = null; // URL of the latest audio alert
    private boolean isLastAudioPlaying = false;
    private boolean isLastAudioPrepared = false;
    private Handler lastAudioProgressHandler; // Handler for the CardView's progress bar
    private Runnable lastAudioProgressRunnable; // Runnable for the handler

    // ActivityResultLauncher for requesting POST_NOTIFICATIONS permission
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i(TAG, "POST_NOTIFICATIONS permission granted.");
                    // You might want to trigger something here if needed after permission grant
                } else {
                    Log.w(TAG, "POST_NOTIFICATIONS permission denied.");
                    showPermissionDeniedDialog();
                }
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // --- Initialize UI Views (Updated) ---
        tvGreeting = findViewById(R.id.tvGreeting);
        tvDeviceStatusLabel = findViewById(R.id.tvDeviceStatusLabel);
        tvDeviceStatus = findViewById(R.id.tvDeviceStatus);
        tvLastNoiseTimeLabel = findViewById(R.id.tvLastNoiseTimeLabel);
        tvLastNoiseTime = findViewById(R.id.tvLastNoiseTime);
        btnMainPlayPause = findViewById(R.id.btnMainPlayPause);
        progressBarMainAudio = findViewById(R.id.progressBarMainAudio);
        btnViewChart = findViewById(R.id.btnViewChart);
        btnViewHistory = findViewById(R.id.btnViewHistory);

        // --- Apply Window Insets ---
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // --- Setup Button Click Listeners ---
        btnMainPlayPause.setOnClickListener(v -> togglePlayPauseLastAudio()); // Listener for the new play/pause button
        btnViewChart.setOnClickListener(v -> {
            Log.d(TAG, "View Chart button clicked.");
            Intent chartIntent = new Intent(MainActivity.this, ChartActivity.class);
            startActivity(chartIntent);
        });
        btnViewHistory.setOnClickListener(v -> {
            Log.d(TAG, "View History button clicked.");
            Intent historyIntent = new Intent(MainActivity.this, HistoryActivity.class);
            startActivity(historyIntent);
        });

        // --- Setup Broadcast Receivers ---
        // Token receiver is removed

        // Receiver for immediate audio playback requests (when app is in foreground)
        playAudioReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "PlayAudioReceiver onReceive triggered with action: " + action);
                if (MyFirebaseMessagingService.ACTION_PLAY_AUDIO_NOW.equals(action)) {
                    String urlToPlay = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_AUDIO_URL);
                    if (urlToPlay != null && !urlToPlay.isEmpty()) {
                        Log.i(TAG, "Received PLAY_AUDIO_NOW broadcast for URL: " + urlToPlay);

                        // Stop background service if it's running
                        stopBackgroundPlaybackService();
                        // Reset any currently playing audio in the main card
                        resetLastAudioPlaybackState();

                        // Store the new URL and update UI
                        lastReceivedAudioUrl = urlToPlay;
                        updateLastNoiseTime(); // Update the timestamp display
                        updateAudioControlsVisibility(true); // Make controls visible

                        // Don't auto-play, let the user click the button
                        // initializeAndPlayLastAudio(lastReceivedAudioUrl);

                    } else {
                        Log.w(TAG, "Received PLAY_AUDIO_NOW broadcast without URL.");
                    }
                }
            }
        };
        playAudioFilter = new IntentFilter(MyFirebaseMessagingService.ACTION_PLAY_AUDIO_NOW);

        // --- Initialize Handler for Last Audio Progress ---
        lastAudioProgressHandler = new Handler(Looper.getMainLooper());
        lastAudioProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (lastAudioMediaPlayer != null && isLastAudioPlaying && isLastAudioPrepared) {
                    try {
                        int currentPosition = lastAudioMediaPlayer.getCurrentPosition();
                        int duration = lastAudioMediaPlayer.getDuration();
                        if (duration > 0) {
                            int progress = (int) (((float) currentPosition / duration) * 100);
                            progressBarMainAudio.setProgress(progress);
                        }
                        // Schedule next update
                        lastAudioProgressHandler.postDelayed(this, 500);
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "IllegalStateException while getting MediaPlayer position/duration for last audio", e);
                        stopLastAudioProgressUpdater();
                    }
                } else {
                    stopLastAudioProgressUpdater();
                }
            }
        };


        // --- Initial Setup ---
        askNotificationPermission();
        // Fetching token is removed
        handleIntent(getIntent()); // Handle initial intent
        updateAudioControlsVisibility(lastReceivedAudioUrl != null); // Set initial visibility
        // TODO: Add logic to fetch initial device status
        updateDeviceStatus("Chưa xác định"); // Placeholder
    }

    // Called when the activity is already running and receives a new intent
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent called with intent: " + intent);
        setIntent(intent); // Update the activity's intent
        handleIntent(intent); // Process the new intent
    }

    // Processes the intent to extract the audio URL for the main card
    private void handleIntent(Intent intent) {
        Log.d(TAG, "Handling intent: " + intent);
        if (intent != null && intent.hasExtra(MyFirebaseMessagingService.EXTRA_AUDIO_URL)) {
            String receivedUrl = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_AUDIO_URL);
            Log.i(TAG, "Received Audio URL from Intent for main card: " + receivedUrl);

            if (receivedUrl != null && !receivedUrl.equals(lastReceivedAudioUrl)) {
                // Stop the background service if user opened the app via notification
                stopBackgroundPlaybackService();
                // Reset playback if a different audio was playing in the card
                resetLastAudioPlaybackState();
                // Store the new URL
                lastReceivedAudioUrl = receivedUrl;
                updateLastNoiseTime(); // Update timestamp display
                updateAudioControlsVisibility(true); // Show controls
            } else if (lastReceivedAudioUrl == null && receivedUrl != null) {
                // Case where there was no URL before
                stopBackgroundPlaybackService();
                lastReceivedAudioUrl = receivedUrl;
                updateLastNoiseTime();
                updateAudioControlsVisibility(true);
            } else {
                // URL is the same or null, no reset needed, but stop background service
                Log.d(TAG, "Received URL is the same as current last URL or null.");
                stopBackgroundPlaybackService();
                // Ensure controls visibility is correct based on existing URL
                updateAudioControlsVisibility(lastReceivedAudioUrl != null);
            }
        } else {
            Log.d(TAG, "Intent does not contain EXTRA_AUDIO_URL for main card.");
            // Ensure controls are hidden if no URL is available
            updateAudioControlsVisibility(lastReceivedAudioUrl != null);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart - Registering receivers");
        isActivityVisible = true;
        // Register local broadcast receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(playAudioReceiver, playAudioFilter);
        // Stop the background playback service if it's running
        stopBackgroundPlaybackService();
        // TODO: Add logic to re-check device status if needed
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop - Unregistering receivers");
        isActivityVisible = false;
        // Unregister local broadcast receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playAudioReceiver);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        isActivityVisible = false;
        // Pause audio playback if the main card audio is playing
        if (lastAudioMediaPlayer != null && isLastAudioPlaying) {
            try {
                lastAudioMediaPlayer.pause();
                isLastAudioPlaying = false;
                stopLastAudioProgressUpdater();
                btnMainPlayPause.setImageResource(android.R.drawable.ic_media_play);
                Log.i(TAG, "Last audio paused due to Activity pause.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error pausing lastAudioMediaPlayer onPause", e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        isActivityVisible = true;
        // Stop background service if it was running
        stopBackgroundPlaybackService();
        // Don't auto-resume audio, user needs to press play again
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        isActivityVisible = false;
        stopLastAudioProgressUpdater(); // Stop handler
        releaseLastAudioMediaPlayer(); // Release MediaPlayer resources
    }

    // --- Permission Handling (Keep as is) ---
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "POST_NOTIFICATIONS permission already granted.");
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                Log.w(TAG, "Showing rationale for POST_NOTIFICATIONS permission.");
                new AlertDialog.Builder(this)
                        .setTitle(R.string.permission_needed_title)
                        .setMessage(R.string.permission_needed_message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) ->
                                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS))
                        .setNegativeButton(R.string.cancel, (dialog, which) ->
                                Log.w(TAG,"User cancelled permission rationale dialog."))
                        .show();
            } else {
                Log.i(TAG, "Requesting POST_NOTIFICATIONS permission...");
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            Log.i(TAG, "POST_NOTIFICATIONS permission not required on this Android version.");
        }
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_needed_title)
                .setMessage(R.string.permission_needed_message)
                .setPositiveButton(R.string.settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    // --- FCM Token Handling (Removed) ---
    // fetchCurrentToken() method is removed

    // --- Main Card Audio Player Handling ---

    private void togglePlayPauseLastAudio() {
        if (lastReceivedAudioUrl == null || lastReceivedAudioUrl.isEmpty()) {
            Toast.makeText(this, R.string.no_audio_url, Toast.LENGTH_SHORT).show();
            Log.w(TAG, "No last audio URL available to play.");
            updateAudioControlsVisibility(false);
            return;
        }

        // Ensure the background playback service is stopped
        stopBackgroundPlaybackService();

        if (lastAudioMediaPlayer != null && isLastAudioPlaying) {
            // If playing, pause it
            try {
                lastAudioMediaPlayer.pause();
                isLastAudioPlaying = false;
                stopLastAudioProgressUpdater();
                btnMainPlayPause.setImageResource(android.R.drawable.ic_media_play);
                Log.i(TAG, "Last audio paused.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error pausing lastAudioMediaPlayer", e);
                handleLastAudioError("Lỗi khi tạm dừng");
            }
        } else if (lastAudioMediaPlayer != null && isLastAudioPrepared) {
            // If prepared but paused, resume playback
            try {
                lastAudioMediaPlayer.start();
                isLastAudioPlaying = true;
                startLastAudioProgressUpdater();
                btnMainPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                Log.i(TAG, "Last audio resumed.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error resuming lastAudioMediaPlayer", e);
                handleLastAudioError("Lỗi khi tiếp tục phát");
            }
        } else {
            // If not prepared or null, initialize and start playback
            initializeAndPlayLastAudio(lastReceivedAudioUrl);
        }
    }

    private void initializeAndPlayLastAudio(String url) {
        resetLastAudioPlaybackState(); // Release any existing player instance first
        Log.i(TAG, "Initializing lastAudioMediaPlayer for URL: " + url);
        updateAudioControlsVisibility(true); // Ensure controls are visible
        progressBarMainAudio.setProgress(0); // Reset progress
        btnMainPlayPause.setEnabled(false); // Disable button while preparing
        btnMainPlayPause.setImageResource(android.R.drawable.ic_media_play); // Show play icon

        Toast.makeText(this, R.string.audio_preparing, Toast.LENGTH_SHORT).show();

        lastAudioMediaPlayer = new MediaPlayer();
        lastAudioMediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
        );
        try {
            lastAudioMediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        } catch (SecurityException e) {
            Log.w(TAG, "Missing WAKE_LOCK permission for lastAudioMediaPlayer", e);
        }

        try {
            lastAudioMediaPlayer.setDataSource(url);
            // Use the activity itself as the listener
            lastAudioMediaPlayer.setOnPreparedListener(this);
            lastAudioMediaPlayer.setOnErrorListener(this);
            lastAudioMediaPlayer.setOnCompletionListener(this);
            lastAudioMediaPlayer.prepareAsync();
            Log.i(TAG, "lastAudioMediaPlayer preparing...");
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException e) {
            Log.e(TAG, "Error initializing lastAudioMediaPlayer", e);
            handleLastAudioError(String.format(getString(R.string.error_playing_audio), e.getMessage()));
        }
    }

    // --- MediaPlayer Listener Callbacks (for lastAudioMediaPlayer) ---

    @Override
    public void onPrepared(MediaPlayer mp) {
        // Check if this callback is for the lastAudioMediaPlayer
        if (mp == lastAudioMediaPlayer) {
            Log.i(TAG, "lastAudioMediaPlayer prepared.");
            isLastAudioPrepared = true;
            btnMainPlayPause.setEnabled(true); // Enable the play/pause button
            try {
                mp.start();
                isLastAudioPlaying = true;
                startLastAudioProgressUpdater(); // Start progress updates
                btnMainPlayPause.setImageResource(android.R.drawable.ic_media_pause); // Update button icon
                Log.i(TAG, "Last audio playback started.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException on lastAudioMediaPlayer.start()", e);
                handleLastAudioError(String.format(getString(R.string.error_playing_audio), "Lỗi khi bắt đầu phát"));
            }
        } else {
            Log.w(TAG, "onPrepared called for an unknown MediaPlayer instance.");
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        // Check if this callback is for the lastAudioMediaPlayer
        if (mp == lastAudioMediaPlayer) {
            Log.e(TAG, "lastAudioMediaPlayer Error: what=" + what + ", extra=" + extra);
            String errorMsg = getMediaPlayerErrorString(what, extra);
            handleLastAudioError(String.format(getString(R.string.error_playing_audio), errorMsg));
            return true; // Indicate error handled
        } else {
            Log.w(TAG, "onError called for an unknown MediaPlayer instance.");
            return false; // Indicate error not handled
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        // Check if this callback is for the lastAudioMediaPlayer
        if (mp == lastAudioMediaPlayer) {
            Log.i(TAG, "lastAudioMediaPlayer playback completed.");
            resetLastAudioPlaybackState(); // Reset state on completion
            Toast.makeText(this, R.string.audio_completed, Toast.LENGTH_SHORT).show();
        } else {
            Log.w(TAG, "onCompletion called for an unknown MediaPlayer instance.");
        }
    }

    // --- Helper Methods for Last Audio Player ---

    private void releaseLastAudioMediaPlayer() {
        if (lastAudioMediaPlayer != null) {
            Log.d(TAG, "Releasing lastAudioMediaPlayer.");
            try {
                if (lastAudioMediaPlayer.isPlaying()) {
                    lastAudioMediaPlayer.stop();
                }
                lastAudioMediaPlayer.reset();
                lastAudioMediaPlayer.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException during lastAudioMediaPlayer release", e);
            } finally {
                lastAudioMediaPlayer = null;
                isLastAudioPrepared = false;
                isLastAudioPlaying = false;
                Log.i(TAG, "lastAudioMediaPlayer released.");
            }
        }
    }

    // Resets the state related to the main card's audio playback
    private void resetLastAudioPlaybackState() {
        Log.d(TAG, "Resetting last audio playback state.");
        releaseLastAudioMediaPlayer();
        stopLastAudioProgressUpdater();
        // Update UI on the main thread
        runOnUiThread(() -> {
            if (btnMainPlayPause != null) {
                btnMainPlayPause.setImageResource(android.R.drawable.ic_media_play);
                // Keep button enabled if URL exists, disable otherwise
                btnMainPlayPause.setEnabled(lastReceivedAudioUrl != null && !lastReceivedAudioUrl.isEmpty());
            }
            if (progressBarMainAudio != null) {
                progressBarMainAudio.setProgress(0);
                // Keep visibility based on URL existence
                progressBarMainAudio.setVisibility( (lastReceivedAudioUrl != null && !lastReceivedAudioUrl.isEmpty()) ? View.VISIBLE : View.INVISIBLE);
            }
        });
    }


    // Centralized handler for lastAudioMediaPlayer errors
    private void handleLastAudioError(String logMessageForUser) {
        Log.e(TAG, "lastAudioMediaPlayer Error: " + logMessageForUser);
        resetLastAudioPlaybackState(); // Reset state on error
        // Show a user-friendly error message
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, logMessageForUser, Toast.LENGTH_LONG).show();
            // Ensure controls are appropriately enabled/disabled after error
            updateAudioControlsVisibility(lastReceivedAudioUrl != null);
        });
    }

    // Helper method to convert MediaPlayer error codes to strings (Keep as is)
    private String getMediaPlayerErrorString(int what, int extra) {
        String whatError;
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_UNKNOWN: whatError = "MEDIA_ERROR_UNKNOWN"; break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED: whatError = "MEDIA_ERROR_SERVER_DIED"; break;
            default: whatError = "Error code: " + what;
        }
        String extraError;
        switch (extra) {
            case MediaPlayer.MEDIA_ERROR_IO: extraError = "MEDIA_ERROR_IO"; break;
            case MediaPlayer.MEDIA_ERROR_MALFORMED: extraError = "MEDIA_ERROR_MALFORMED"; break;
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED: extraError = "MEDIA_ERROR_UNSUPPORTED"; break;
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT: extraError = "MEDIA_ERROR_TIMED_OUT"; break;
            case -2147483648: extraError = "MEDIA_ERROR_SYSTEM (-2147483648)"; break;
            default: extraError = "Extra code: " + extra;
        }
        return whatError + " (" + extraError + ")";
    }

    // --- Progress Updater Handling for Last Audio ---
    private void startLastAudioProgressUpdater() {
        stopLastAudioProgressUpdater(); // Stop previous one if any
        lastAudioProgressHandler.post(lastAudioProgressRunnable);
        Log.d(TAG, "Started last audio progress updater.");
    }

    private void stopLastAudioProgressUpdater() {
        lastAudioProgressHandler.removeCallbacks(lastAudioProgressRunnable);
        Log.d(TAG, "Stopped last audio progress updater.");
    }

    // --- UI Update Helpers ---

    // Updates the visibility of play/pause button and progress bar
    private void updateAudioControlsVisibility(boolean show) {
        runOnUiThread(() -> {
            int visibility = show ? View.VISIBLE : View.INVISIBLE; // Use INVISIBLE to maintain layout space
            if (btnMainPlayPause != null) {
                btnMainPlayPause.setVisibility(visibility);
                btnMainPlayPause.setEnabled(show); // Also enable/disable
            }
            if (progressBarMainAudio != null) {
                progressBarMainAudio.setVisibility(visibility);
                if (!show) {
                    progressBarMainAudio.setProgress(0); // Reset progress when hiding
                }
            }
        });
    }

    // Updates the last noise time display
    private void updateLastNoiseTime() {
        runOnUiThread(() -> {
            if (tvLastNoiseTime != null) {
                if (lastReceivedAudioUrl != null) {
                    // Format the current time as the "last noise time"
                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
                    String formattedTime = sdf.format(new Date());
                    tvLastNoiseTime.setText(formattedTime);
                } else {
                    tvLastNoiseTime.setText(R.string.last_noise_time_none);
                }
            }
        });
    }

    // Updates the device status display (Placeholder)
    private void updateDeviceStatus(String status) {
        runOnUiThread(() -> {
            if (tvDeviceStatus != null) {
                tvDeviceStatus.setText(status);
                // TODO: Change text color based on status (e.g., green for online, red for offline)
            }
        });
    }


    // --- Background Service Control (Keep as is) ---
    private void stopBackgroundPlaybackService() {
        Log.d(TAG, "Requesting to stop AudioPlaybackService.");
        Intent stopIntent = new Intent(this, AudioPlaybackService.class);
        stopIntent.setAction(AudioPlaybackService.ACTION_STOP);
        stopService(stopIntent);
    }
    // ---------------------------------
}
