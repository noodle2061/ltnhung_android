package com.example.nhandientienghet; // Thay đổi package name nếu cần

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
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;

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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public class MainActivity extends AppCompatActivity implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {

    private static final String TAG = "MainActivity";
    // --- DÒNG KHAI BÁO QUAN TRỌNG ---
    public static boolean isActivityVisible; // Biến static để service kiểm tra
    // ---------------------------------

    // --- Các thành phần UI ---
    private TextView tvCurrentToken;
    private TextView tvLogDisplay;
    private ScrollView scrollViewLog;
    private Button btnClearLog;
    private Button btnPlayAudio;
    private TextView tvAudioStatus;
    // ---------------------------

    private BroadcastReceiver logReceiver;
    private BroadcastReceiver playAudioReceiver; // Receiver mới để nghe lệnh phát nhạc
    private IntentFilter logFilter;
    private IntentFilter playAudioFilter; // Filter mới

    // --- Biến cho MediaPlayer ---
    private MediaPlayer mediaPlayer;
    private String currentAudioUrl = null;
    private boolean isAudioPrepared = false;
    // ---------------------------


    // ActivityResultLauncher để xin quyền thông báo
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i(TAG, "POST_NOTIFICATIONS permission granted.");
                    appendLog("Quyền gửi thông báo đã được cấp.");
                    fetchCurrentToken();
                } else {
                    Log.w(TAG, "POST_NOTIFICATIONS permission denied.");
                    appendLog("Quyền gửi thông báo bị từ chối.");
                    showPermissionDeniedDialog();
                }
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Ánh xạ các view
        tvCurrentToken = findViewById(R.id.tvCurrentToken);
        tvLogDisplay = findViewById(R.id.tvLogDisplay);
        scrollViewLog = findViewById(R.id.scrollViewLog);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnPlayAudio = findViewById(R.id.btnPlayAudio);
        tvAudioStatus = findViewById(R.id.tvAudioStatus);

        tvLogDisplay.setMovementMethod(new ScrollingMovementMethod());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Listener cho nút Clear Log
        btnClearLog.setOnClickListener(v -> {
            tvLogDisplay.setText("");
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show();
        });

        // Listener cho nút Play Audio
        btnPlayAudio.setOnClickListener(v -> {
            togglePlayPauseAudio();
        });

        // Cấu hình BroadcastReceiver để nhận log và token
        logReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "LogReceiver onReceive triggered with action: " + action);
                if (MyFirebaseMessagingService.ACTION_UPDATE_LOG.equals(action)) {
                    String logMessage = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_LOG_MESSAGE);
                    if (logMessage != null) {
                        appendLog(logMessage);
                    }
                } else if (MyFirebaseMessagingService.ACTION_UPDATE_TOKEN.equals(action)) {
                    String token = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_FCM_TOKEN);
                    tvCurrentToken.setText(token != null ? token : getString(R.string.token_not_available));
                }
            }
        };
        logFilter = new IntentFilter();
        logFilter.addAction(MyFirebaseMessagingService.ACTION_UPDATE_LOG);
        logFilter.addAction(MyFirebaseMessagingService.ACTION_UPDATE_TOKEN);

        // Cấu hình BroadcastReceiver mới để nhận lệnh phát nhạc
        playAudioReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "PlayAudioReceiver onReceive triggered with action: " + action);
                if (MyFirebaseMessagingService.ACTION_PLAY_AUDIO_NOW.equals(action)) {
                    String urlToPlay = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_AUDIO_URL);
                    if (urlToPlay != null && !urlToPlay.isEmpty()) {
                        Log.i(TAG, "Received PLAY_AUDIO_NOW broadcast for URL: " + urlToPlay);
                        appendLog("Nhận lệnh phát audio tự động: " + urlToPlay);
                        // Dừng service phát nhạc nền nếu nó đang chạy (tránh phát 2 lần)
                        stopBackgroundPlaybackService();
                        // Cập nhật URL và bắt đầu phát bằng player của Activity
                        currentAudioUrl = urlToPlay;
                        btnPlayAudio.setEnabled(true); // Đảm bảo nút bật
                        initializeAndPlayAudio(currentAudioUrl);
                    } else {
                        Log.w(TAG, "Received PLAY_AUDIO_NOW broadcast without URL.");
                    }
                }
            }
        };
        playAudioFilter = new IntentFilter(MyFirebaseMessagingService.ACTION_PLAY_AUDIO_NOW);

        // Xin quyền thông báo và lấy token
        askNotificationPermission();
        fetchCurrentToken();

        appendLog("Ứng dụng đã khởi động.");

        // Xử lý Intent khi Activity được tạo
        handleIntent(getIntent());
    }

    // Xử lý Intent khi Activity đã chạy và nhận Intent mới
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent called with intent: " + intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // Hàm xử lý Intent để lấy Audio URL và cập nhật UI
    private void handleIntent(Intent intent) {
        Log.d(TAG, "Handling intent: " + intent);
        if (intent != null && intent.hasExtra(MyFirebaseMessagingService.EXTRA_AUDIO_URL)) {
            String receivedUrl = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_AUDIO_URL);
            Log.i(TAG, "Received Audio URL from Intent: " + receivedUrl);
            appendLog("Đã nhận URL audio từ Intent: " + receivedUrl);

            // Chỉ cập nhật và reset nếu URL mới khác URL cũ hoặc URL cũ là null
            if (receivedUrl != null && !receivedUrl.equals(currentAudioUrl)) {
                currentAudioUrl = receivedUrl;
                // Dừng service phát nhạc nền nếu nó đang chạy (vì user đã mở app)
                stopBackgroundPlaybackService();
                // Kích hoạt nút Play và reset trạng thái
                btnPlayAudio.setEnabled(true);
                tvAudioStatus.setText(R.string.audio_status_idle);
                releaseMediaPlayer(); // Dừng và giải phóng trình phát cũ của activity
                isAudioPrepared = false;
                btnPlayAudio.setText(R.string.play_audio);
            } else if (currentAudioUrl == null) {
                currentAudioUrl = receivedUrl;
                stopBackgroundPlaybackService(); // Dừng service nền
                btnPlayAudio.setEnabled(true);
                tvAudioStatus.setText(R.string.audio_status_idle);
                releaseMediaPlayer();
                isAudioPrepared = false;
                btnPlayAudio.setText(R.string.play_audio);
            } else {
                Log.d(TAG, "Received URL is the same as current URL, no UI reset needed.");
                btnPlayAudio.setEnabled(currentAudioUrl != null && !currentAudioUrl.isEmpty());
                // Nếu activity được mở lại từ notification của service nền, dừng service đó
                stopBackgroundPlaybackService();
            }

        } else {
            Log.d(TAG, "Intent does not contain EXTRA_AUDIO_URL.");
            if (currentAudioUrl == null) {
                btnPlayAudio.setEnabled(false);
                tvAudioStatus.setText(R.string.no_audio_url);
            } else {
                btnPlayAudio.setEnabled(true);
            }
        }
    }


    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart - Registering receivers");
        isActivityVisible = true; // Đánh dấu Activity đang hiển thị
        LocalBroadcastManager.getInstance(this).registerReceiver(logReceiver, logFilter);
        LocalBroadcastManager.getInstance(this).registerReceiver(playAudioReceiver, playAudioFilter); // Đăng ký receiver mới
        // Khi activity hiện lên, nếu có service nền đang chạy, dừng nó đi
        stopBackgroundPlaybackService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop - Unregistering receivers");
        isActivityVisible = false; // Đánh dấu Activity không còn hiển thị
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playAudioReceiver); // Hủy đăng ký receiver mới
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        isActivityVisible = false; // Coi như không visible khi pause
        // Tạm dừng nhạc nếu đang phát khi Activity bị pause
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updateAudioStatus(getString(R.string.audio_status_paused));
            btnPlayAudio.setText(R.string.play_audio);
            appendLog("Tự động tạm dừng audio (Activity) do Activity bị Pause.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        isActivityVisible = true; // Đánh dấu lại là visible
        // Khi quay lại app, dừng service nền nếu có
        stopBackgroundPlaybackService();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        isActivityVisible = false; // Đảm bảo là false khi bị hủy
        releaseMediaPlayer();
    }


    // Hàm thêm log vào TextView
    private void appendLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        final String logLine = timestamp + ": " + message + "\n";
        runOnUiThread(() -> {
            if (tvLogDisplay != null) {
                tvLogDisplay.append(logLine);
            }
            if (scrollViewLog != null) {
                scrollViewLog.post(() -> {
                    if (scrollViewLog != null) {
                        scrollViewLog.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
        Log.i(TAG, "Log appended: " + message);
    }

    // Hàm xin quyền thông báo
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                appendLog("Quyền gửi thông báo: Đã cấp.");
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                appendLog("Quyền gửi thông báo: Cần giải thích.");
                new AlertDialog.Builder(this)
                        .setTitle(R.string.permission_needed_title)
                        .setMessage(R.string.permission_needed_message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS))
                        .setNegativeButton(R.string.cancel, (dialog, which) -> appendLog("Người dùng đã hủy yêu cầu cấp quyền."))
                        .show();
            } else {
                appendLog("Quyền gửi thông báo: Đang yêu cầu...");
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            appendLog("Quyền gửi thông báo: Không cần thiết trên phiên bản Android này.");
        }
    }

    // Hàm hiển thị dialog khi quyền bị từ chối
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

    // Hàm lấy token hiện tại
    private void fetchCurrentToken() {
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                        appendLog("Lỗi khi lấy FCM token: " + (task.getException() != null ? task.getException().getMessage() : "Unknown error"));
                        tvCurrentToken.setText(R.string.token_not_available);
                        return;
                    }
                    String token = task.getResult();
                    Log.d(TAG, "Current FCM Token: " + token);
                    tvCurrentToken.setText(token != null ? token : getString(R.string.token_not_available));
                });
    }

    // --- Các hàm xử lý MediaPlayer của Activity ---

    private void togglePlayPauseAudio() {
        if (currentAudioUrl == null || currentAudioUrl.isEmpty()) {
            Toast.makeText(this, R.string.no_audio_url, Toast.LENGTH_SHORT).show();
            appendLog("Không có URL audio để phát.");
            updateAudioStatus(getString(R.string.no_audio_url));
            btnPlayAudio.setEnabled(false);
            return;
        }

        // Dừng service nền trước khi thao tác với player của activity
        stopBackgroundPlaybackService();

        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updateAudioStatus(getString(R.string.audio_status_paused));
            btnPlayAudio.setText(R.string.play_audio);
            appendLog("Đã tạm dừng audio (Activity).");
        }
        else if (mediaPlayer != null && isAudioPrepared) {
            mediaPlayer.start();
            updateAudioStatus(getString(R.string.audio_status_playing));
            btnPlayAudio.setText(R.string.pause_audio);
            appendLog("Đang tiếp tục phát audio (Activity).");
        }
        else {
            initializeAndPlayAudio(currentAudioUrl);
        }
    }

    // Hàm này giờ chỉ quản lý MediaPlayer của Activity
    private void initializeAndPlayAudio(String url) {
        releaseMediaPlayer(); // Giải phóng player cũ của activity
        appendLog("Đang khởi tạo MediaPlayer (Activity) cho URL: " + url);
        updateAudioStatus(getString(R.string.audio_status_preparing));
        btnPlayAudio.setEnabled(false);
        btnPlayAudio.setText(R.string.play_audio);

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
        );
        try {
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        } catch (SecurityException e) {
            Log.w(TAG, "Missing WAKE_LOCK permission", e);
            appendLog("Cảnh báo: Thiếu quyền WAKE_LOCK.");
        }

        try {
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnErrorListener(this);
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.prepareAsync();
            appendLog("MediaPlayer (Activity) đang chuẩn bị...");
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException e) {
            Log.e(TAG, "Error initializing MediaPlayer (Activity)", e);
            handleMediaPlayerError(String.format(getString(R.string.error_playing_audio), e.getMessage()));
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        // Callback này dành cho MediaPlayer của Activity
        Log.i(TAG, "MediaPlayer (Activity) prepared.");
        appendLog("MediaPlayer (Activity) đã sẵn sàng.");
        isAudioPrepared = true;
        btnPlayAudio.setEnabled(true);
        try {
            mp.start();
            updateAudioStatus(getString(R.string.audio_status_playing));
            btnPlayAudio.setText(R.string.pause_audio);
            appendLog("Bắt đầu phát audio (Activity).");
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException on MediaPlayer.start() (Activity)", e);
            handleMediaPlayerError(String.format(getString(R.string.error_playing_audio), "Lỗi khi bắt đầu phát"));
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        // Callback này dành cho MediaPlayer của Activity
        Log.e(TAG, "MediaPlayer (Activity) Error: what=" + what + ", extra=" + extra);
        String errorMsg = getMediaPlayerErrorString(what, extra);
        handleMediaPlayerError(String.format(getString(R.string.error_playing_audio), errorMsg));
        return true;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        // Callback này dành cho MediaPlayer của Activity
        Log.i(TAG, "MediaPlayer (Activity) playback completed.");
        appendLog("Phát audio (Activity) hoàn tất.");
        updateAudioStatus(getString(R.string.audio_status_completed));
        releaseMediaPlayer(); // Giải phóng khi phát xong
        btnPlayAudio.setText(R.string.play_audio);
        isAudioPrepared = false;
        btnPlayAudio.setEnabled(currentAudioUrl != null && !currentAudioUrl.isEmpty());
    }

    // Hàm này giờ chỉ giải phóng MediaPlayer của Activity
    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            Log.d(TAG, "Releasing MediaPlayer (Activity).");
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException during MediaPlayer release (Activity)", e);
            } finally {
                mediaPlayer = null;
                isAudioPrepared = false;
                appendLog("Đã giải phóng MediaPlayer (Activity).");
            }
        }
    }

    private void updateAudioStatus(String status) {
        runOnUiThread(() -> {
            if (tvAudioStatus != null) tvAudioStatus.setText(status);
        });
    }

    // Hàm xử lý chung khi có lỗi MediaPlayer của Activity
    private void handleMediaPlayerError(String logMessage) {
        appendLog(logMessage);
        updateAudioStatus(getString(R.string.audio_status_error));
        releaseMediaPlayer();
        runOnUiThread(() -> {
            if (btnPlayAudio != null) {
                btnPlayAudio.setEnabled(currentAudioUrl != null && !currentAudioUrl.isEmpty());
                btnPlayAudio.setText(R.string.play_audio);
            }
        });
        isAudioPrepared = false;
    }

    // Hàm helper lấy chuỗi lỗi
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
            case -2147483648: extraError = "MEDIA_ERROR_SYSTEM"; break;
            default: extraError = "Extra code: " + extra;
        }
        return whatError + " (" + extraError + ")";
    }

    // Hàm để dừng service phát nhạc nền
    private void stopBackgroundPlaybackService() {
        Log.d(TAG, "Requesting to stop AudioPlaybackService.");
        Intent stopIntent = new Intent(this, AudioPlaybackService.class);
        stopIntent.setAction(AudioPlaybackService.ACTION_STOP);
        // Không cần startService, chỉ cần stopService
        stopService(stopIntent);
    }

    // ---------------------------------
}
