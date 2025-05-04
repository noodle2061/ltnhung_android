package com.example.nhandientienghet; // Đảm bảo package name này đúng với dự án của bạn

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
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;

public class MainActivity extends AppCompatActivity implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {

    private static final String TAG = "MainActivity";
    // Static variable to check if the activity is currently visible to the user
    // Used by MyFirebaseMessagingService to decide how to handle incoming messages
    public static boolean isActivityVisible;

    // --- UI Components ---
    private TextView tvCurrentToken;
    private Button btnPlayAudio;
    private TextView tvAudioStatus;
    private Button btnViewChart;
    private Button btnViewHistory; // Button to open the history activity

    // --- Broadcast Receivers and Filters ---
    // Receiver for handling PLAY_AUDIO_NOW action from MyFirebaseMessagingService
    private BroadcastReceiver playAudioReceiver;
    // Receiver for handling token updates from MyFirebaseMessagingService
    private BroadcastReceiver tokenReceiver;
    private IntentFilter playAudioFilter;
    private IntentFilter tokenFilter;

    // --- MediaPlayer Components ---
    private MediaPlayer mediaPlayer; // For playing audio within the activity
    private String currentAudioUrl = null; // URL of the audio to be played
    private boolean isAudioPrepared = false; // Flag to check if MediaPlayer is prepared

    // ActivityResultLauncher for requesting POST_NOTIFICATIONS permission on Android 13+
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i(TAG, "POST_NOTIFICATIONS permission granted.");
                    // Permission granted, fetch the current FCM token
                    fetchCurrentToken();
                } else {
                    // Permission denied, show a dialog explaining why it's needed
                    Log.w(TAG, "POST_NOTIFICATIONS permission denied.");
                    showPermissionDeniedDialog();
                }
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        // Enable edge-to-edge display
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // --- Initialize UI Views ---
        tvCurrentToken = findViewById(R.id.tvCurrentToken);
        btnPlayAudio = findViewById(R.id.btnPlayAudio);
        tvAudioStatus = findViewById(R.id.tvAudioStatus);
        btnViewChart = findViewById(R.id.btnViewChart);
        btnViewHistory = findViewById(R.id.btnViewHistory); // Find the history button

        // --- Apply Window Insets for Edge-to-Edge ---
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // --- Setup Button Click Listeners ---
        // Listener for the Play/Pause Audio button
        btnPlayAudio.setOnClickListener(v -> togglePlayPauseAudio());

        // Listener for the View Chart button
        btnViewChart.setOnClickListener(v -> {
            Log.d(TAG, "View Chart button clicked.");
            Intent chartIntent = new Intent(MainActivity.this, ChartActivity.class);
            startActivity(chartIntent);
        });

        // Listener for the View History button
        btnViewHistory.setOnClickListener(v -> {
            Log.d(TAG, "View History button clicked.");
            Intent historyIntent = new Intent(MainActivity.this, HistoryActivity.class);
            startActivity(historyIntent);
        });

        // --- Setup Broadcast Receivers ---
        // Receiver for FCM token updates
        tokenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "TokenReceiver onReceive triggered with action: " + action);
                if (MyFirebaseMessagingService.ACTION_UPDATE_TOKEN.equals(action)) {
                    String token = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_FCM_TOKEN);
                    tvCurrentToken.setText(token != null ? token : getString(R.string.token_not_available));
                }
            }
        };
        tokenFilter = new IntentFilter(MyFirebaseMessagingService.ACTION_UPDATE_TOKEN);

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
                        // Stop background service if it's running to avoid double playback
                        stopBackgroundPlaybackService();
                        // Update the URL and start playback in this activity
                        currentAudioUrl = urlToPlay;
                        btnPlayAudio.setEnabled(true); // Ensure button is enabled
                        initializeAndPlayAudio(currentAudioUrl);
                    } else {
                        Log.w(TAG, "Received PLAY_AUDIO_NOW broadcast without URL.");
                    }
                }
            }
        };
        playAudioFilter = new IntentFilter(MyFirebaseMessagingService.ACTION_PLAY_AUDIO_NOW);

        // --- Initial Setup ---
        // Request notification permission (required for Android 13+)
        askNotificationPermission();
        // Fetch the current FCM token
        fetchCurrentToken();
        // Handle any intent that might have started this activity (e.g., from a notification click)
        handleIntent(getIntent());
    }

    // Called when the activity is already running and receives a new intent
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent called with intent: " + intent);
        setIntent(intent); // Update the activity's intent
        handleIntent(intent); // Process the new intent
    }

    // Processes the intent to extract the audio URL and update the UI accordingly
    private void handleIntent(Intent intent) {
        Log.d(TAG, "Handling intent: " + intent);
        // Check if the intent contains an audio URL extra
        if (intent != null && intent.hasExtra(MyFirebaseMessagingService.EXTRA_AUDIO_URL)) {
            String receivedUrl = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_AUDIO_URL);
            Log.i(TAG, "Received Audio URL from Intent: " + receivedUrl);

            // Only update and reset player if the URL is new or was previously null
            if (receivedUrl != null && !receivedUrl.equals(currentAudioUrl)) {
                currentAudioUrl = receivedUrl;
                // Stop the background service if user opened the app via notification
                stopBackgroundPlaybackService();
                // Enable play button and reset player state
                btnPlayAudio.setEnabled(true);
                tvAudioStatus.setText(R.string.audio_status_idle);
                releaseMediaPlayer(); // Stop and release the previous player instance
                isAudioPrepared = false;
                btnPlayAudio.setText(R.string.play_audio);
            } else if (currentAudioUrl == null && receivedUrl != null) {
                // Case where there was no URL before
                currentAudioUrl = receivedUrl;
                stopBackgroundPlaybackService();
                btnPlayAudio.setEnabled(true);
                tvAudioStatus.setText(R.string.audio_status_idle);
                releaseMediaPlayer();
                isAudioPrepared = false;
                btnPlayAudio.setText(R.string.play_audio);
            } else {
                // URL is the same, no reset needed, but ensure button state is correct
                Log.d(TAG, "Received URL is the same as current URL or null, no UI reset needed.");
                btnPlayAudio.setEnabled(currentAudioUrl != null && !currentAudioUrl.isEmpty());
                // Still stop the background service if the app was brought to front
                stopBackgroundPlaybackService();
            }

        } else {
            // Intent does not contain the audio URL extra
            Log.d(TAG, "Intent does not contain EXTRA_AUDIO_URL.");
            // Disable play button if no URL is available
            if (currentAudioUrl == null) {
                btnPlayAudio.setEnabled(false);
                tvAudioStatus.setText(R.string.no_audio_url);
            } else {
                // Keep button enabled if a previous URL exists
                btnPlayAudio.setEnabled(true);
            }
        }
    }


    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart - Registering receivers");
        isActivityVisible = true; // Mark activity as visible
        // Register local broadcast receivers
        LocalBroadcastManager.getInstance(this).registerReceiver(tokenReceiver, tokenFilter);
        LocalBroadcastManager.getInstance(this).registerReceiver(playAudioReceiver, playAudioFilter);
        // Stop the background playback service if it's running when the activity becomes visible
        stopBackgroundPlaybackService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop - Unregistering receivers");
        isActivityVisible = false; // Mark activity as not visible
        // Unregister local broadcast receivers to prevent memory leaks
        LocalBroadcastManager.getInstance(this).unregisterReceiver(tokenReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playAudioReceiver);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        isActivityVisible = false; // Consider activity not visible when paused
        // Pause audio playback if the activity is paused
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            try {
                mediaPlayer.pause();
                updateAudioStatus(getString(R.string.audio_status_paused));
                btnPlayAudio.setText(R.string.play_audio);
                Log.i(TAG, "Audio (Activity) paused due to Activity pause.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error pausing MediaPlayer onPause", e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        isActivityVisible = true; // Mark activity as visible again
        // Stop background service if it was running while the app was paused/stopped
        stopBackgroundPlaybackService();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        isActivityVisible = false; // Ensure flag is false when destroyed
        releaseMediaPlayer(); // Release MediaPlayer resources
    }


    // --- Permission Handling ---

    // Asks for POST_NOTIFICATIONS permission on Android 13+
    private void askNotificationPermission() {
        // Only needed on Android 13 (API level 33) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                // Permission already granted
                Log.i(TAG, "POST_NOTIFICATIONS permission already granted.");
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // Explain to the user why the permission is needed
                Log.w(TAG, "Showing rationale for POST_NOTIFICATIONS permission.");
                new AlertDialog.Builder(this)
                        .setTitle(R.string.permission_needed_title)
                        .setMessage(R.string.permission_needed_message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) ->
                                // Request the permission again
                                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS))
                        .setNegativeButton(R.string.cancel, (dialog, which) ->
                                Log.w(TAG,"User cancelled permission rationale dialog."))
                        .show();
            } else {
                // Directly request the permission
                Log.i(TAG, "Requesting POST_NOTIFICATIONS permission...");
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            // Permission not needed for older Android versions
            Log.i(TAG, "POST_NOTIFICATIONS permission not required on this Android version.");
        }
    }

    // Shows a dialog guiding the user to app settings if permission was denied
    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_needed_title)
                .setMessage(R.string.permission_needed_message)
                .setPositiveButton(R.string.settings, (dialog, which) -> {
                    // Open app settings
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    // --- FCM Token Handling ---

    // Fetches the current FCM registration token and updates the UI
    private void fetchCurrentToken() {
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                        tvCurrentToken.setText(R.string.token_not_available);
                        return;
                    }
                    // Get new FCM registration token
                    String token = task.getResult();
                    Log.d(TAG, "Current FCM Token: " + token);
                    // Display the token (or placeholder if null)
                    tvCurrentToken.setText(token != null ? token : getString(R.string.token_not_available));
                    // Note: Sending the token to the server is handled by MyFirebaseMessagingService.onNewToken
                });
    }

    // --- Activity MediaPlayer Handling ---

    // Toggles between playing and pausing the audio associated with currentAudioUrl
    private void togglePlayPauseAudio() {
        // Check if a valid audio URL is available
        if (currentAudioUrl == null || currentAudioUrl.isEmpty()) {
            Toast.makeText(this, R.string.no_audio_url, Toast.LENGTH_SHORT).show();
            Log.w(TAG, "No audio URL available to play.");
            updateAudioStatus(getString(R.string.no_audio_url));
            btnPlayAudio.setEnabled(false);
            return;
        }

        // Ensure the background playback service is stopped before using the activity's player
        stopBackgroundPlaybackService();

        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            // If playing, pause it
            try {
                mediaPlayer.pause();
                updateAudioStatus(getString(R.string.audio_status_paused));
                btnPlayAudio.setText(R.string.play_audio);
                Log.i(TAG, "Audio (Activity) paused.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error pausing MediaPlayer", e);
                handleMediaPlayerError("Lỗi khi tạm dừng");
            }
        } else if (mediaPlayer != null && isAudioPrepared) {
            // If prepared but paused, resume playback
            try {
                mediaPlayer.start();
                updateAudioStatus(getString(R.string.audio_status_playing));
                btnPlayAudio.setText(R.string.pause_audio);
                Log.i(TAG, "Audio (Activity) resumed.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error resuming MediaPlayer", e);
                handleMediaPlayerError("Lỗi khi tiếp tục phát");
            }
        } else {
            // If not prepared or null, initialize and start playback
            initializeAndPlayAudio(currentAudioUrl);
        }
    }

    // Initializes the MediaPlayer instance for the given URL and starts preparation
    private void initializeAndPlayAudio(String url) {
        releaseMediaPlayer(); // Release any existing player instance first
        Log.i(TAG, "Initializing MediaPlayer (Activity) for URL: " + url);
        updateAudioStatus(getString(R.string.audio_status_preparing));
        btnPlayAudio.setEnabled(false); // Disable button while preparing
        btnPlayAudio.setText(R.string.play_audio); // Reset button text

        mediaPlayer = new MediaPlayer();
        // Set audio attributes for music playback
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
        );
        // Request a partial wake lock to keep CPU running during playback (requires WAKE_LOCK permission)
        try {
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        } catch (SecurityException e) {
            Log.w(TAG, "Missing WAKE_LOCK permission for MediaPlayer", e);
            // Playback might stop if device sleeps deeply
        }

        try {
            // Set the data source (URL)
            mediaPlayer.setDataSource(url);
            // Set listeners for various MediaPlayer events
            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnErrorListener(this);
            mediaPlayer.setOnCompletionListener(this);
            // Start asynchronous preparation
            mediaPlayer.prepareAsync();
            Log.i(TAG, "MediaPlayer (Activity) preparing...");
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException e) {
            // Handle errors during initialization
            Log.e(TAG, "Error initializing MediaPlayer (Activity)", e);
            handleMediaPlayerError(String.format(getString(R.string.error_playing_audio), e.getMessage()));
        }
    }

    // Called when MediaPlayer is prepared and ready to play
    @Override
    public void onPrepared(MediaPlayer mp) {
        Log.i(TAG, "MediaPlayer (Activity) prepared.");
        isAudioPrepared = true;
        btnPlayAudio.setEnabled(true); // Enable the play button
        try {
            // Start playback
            mp.start();
            updateAudioStatus(getString(R.string.audio_status_playing));
            btnPlayAudio.setText(R.string.pause_audio); // Update button text
            Log.i(TAG, "Audio (Activity) playback started.");
        } catch (IllegalStateException e) {
            // Handle error if start() is called in an invalid state
            Log.e(TAG, "IllegalStateException on MediaPlayer.start() (Activity)", e);
            handleMediaPlayerError(String.format(getString(R.string.error_playing_audio), "Lỗi khi bắt đầu phát"));
        }
    }

    // Called when an error occurs during playback
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        // Log the error details
        Log.e(TAG, "MediaPlayer (Activity) Error: what=" + what + ", extra=" + extra);
        String errorMsg = getMediaPlayerErrorString(what, extra); // Get a user-friendly error string
        // Handle the error (update UI, release player)
        handleMediaPlayerError(String.format(getString(R.string.error_playing_audio), errorMsg));
        return true; // Indicate that the error has been handled
    }

    // Called when playback reaches the end of the media
    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.i(TAG, "MediaPlayer (Activity) playback completed.");
        updateAudioStatus(getString(R.string.audio_status_completed));
        releaseMediaPlayer(); // Release the player resources
        btnPlayAudio.setText(R.string.play_audio); // Reset button text
        isAudioPrepared = false;
        // Re-enable button only if a valid URL is still present
        btnPlayAudio.setEnabled(currentAudioUrl != null && !currentAudioUrl.isEmpty());
    }

    // Releases the MediaPlayer resources safely
    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            Log.d(TAG, "Releasing MediaPlayer (Activity).");
            try {
                // Check states before calling methods to avoid IllegalStateException
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset(); // Reset player to idle state
                mediaPlayer.release(); // Release system resources
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException during MediaPlayer release (Activity)", e);
            } finally {
                mediaPlayer = null; // Set to null after release
                isAudioPrepared = false;
                Log.i(TAG, "MediaPlayer (Activity) released.");
            }
        }
    }

    // Updates the audio status TextView on the UI thread
    private void updateAudioStatus(String status) {
        runOnUiThread(() -> {
            if (tvAudioStatus != null) tvAudioStatus.setText(status);
        });
    }

    // Centralized handler for MediaPlayer errors
    private void handleMediaPlayerError(String logMessageForUser) {
        Log.e(TAG, "MediaPlayer Error (Activity): " + logMessageForUser); // Log detailed error
        updateAudioStatus(getString(R.string.audio_status_error)); // Update UI status
        releaseMediaPlayer(); // Release the faulty player
        // Update UI elements on the main thread
        runOnUiThread(() -> {
            if (btnPlayAudio != null) {
                // Re-enable button only if a URL exists, reset text
                btnPlayAudio.setEnabled(currentAudioUrl != null && !currentAudioUrl.isEmpty());
                btnPlayAudio.setText(R.string.play_audio);
            }
            // Show a user-friendly error message
            Toast.makeText(MainActivity.this, logMessageForUser, Toast.LENGTH_LONG).show();
        });
        isAudioPrepared = false;
    }

    // Helper method to convert MediaPlayer error codes to strings
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
            // <<< SỬA LỖI Ở ĐÂY: Dùng giá trị số nguyên thay vì hằng số >>>
            case -2147483648: extraError = "MEDIA_ERROR_SYSTEM (-2147483648)"; break;
            // <<< KẾT THÚC SỬA LỖI >>>
            default: extraError = "Extra code: " + extra;
        }
        return whatError + " (" + extraError + ")";
    }

    // --- Background Service Control ---

    // Sends an intent to stop the AudioPlaybackService
    private void stopBackgroundPlaybackService() {
        Log.d(TAG, "Requesting to stop AudioPlaybackService.");
        Intent stopIntent = new Intent(this, AudioPlaybackService.class);
        stopIntent.setAction(AudioPlaybackService.ACTION_STOP);
        // Use stopService() to request the service to stop
        stopService(stopIntent);
    }
    // ---------------------------------
}
