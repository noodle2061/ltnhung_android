package com.example.nhandientienghet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast; // Thêm để hiển thị thông báo lỗi ngắn

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
// import androidx.localbroadcastmanager.content.LocalBroadcastManager; // Gửi log về MainActivity - Removed

import java.io.IOException;

public class AudioPlaybackService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {

    private static final String TAG = "AudioPlaybackService";
    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    public static final String ACTION_PLAY = "com.example.nhandientienghet.action.PLAY";
    public static final String ACTION_STOP = "com.example.nhandientienghet.action.STOP";
    public static final String EXTRA_AUDIO_URL = "com.example.nhandientienghet.extra.AUDIO_URL"; // Giống key trong FCM Service

    private static final String CHANNEL_ID = "AudioPlaybackChannel";
    private static final int NOTIFICATION_ID = 2; // ID khác với service TCP cũ (nếu có) và FCM

    private String currentUrl = null;
    private boolean isPreparing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createNotificationChannel();
        // Khởi tạo WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NhanDienTiengHet::PlaybackWakeLockTag");
            wakeLock.setReferenceCounted(false); // Không tự động release khi count về 0
        } else {
            Log.e(TAG, "Failed to get PowerManager service.");
        }
        // sendLogUpdate("AudioPlaybackService đã tạo."); // Removed
        Log.i(TAG, "AudioPlaybackService created.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        String audioUrl = intent != null ? intent.getStringExtra(EXTRA_AUDIO_URL) : null;
        Log.d(TAG, "onStartCommand - Action: " + action + ", URL: " + audioUrl);

        if (ACTION_PLAY.equals(action) && audioUrl != null) {
            // Nếu đang phát URL khác, dừng lại trước
            if (mediaPlayer != null && mediaPlayer.isPlaying() && !audioUrl.equals(currentUrl)) {
                Log.w(TAG, "Playing different URL, stopping previous playback.");
                stopPlayback();
            }
            // Chỉ bắt đầu phát nếu chưa phát hoặc URL khác
            if (mediaPlayer == null || !audioUrl.equals(currentUrl)) {
                currentUrl = audioUrl;
                startForeground(NOTIFICATION_ID, createNotification("Đang chuẩn bị...", audioUrl, false));
                initializeAndPlay(audioUrl);
            } else if (mediaPlayer != null && !mediaPlayer.isPlaying() && !isPreparing) {
                // Nếu mediaPlayer tồn tại, không phát, không chuẩn bị (tức là đã pause hoặc complete) -> start lại
                Log.d(TAG, "Resuming playback for the same URL.");
                try {
                    mediaPlayer.start();
                    startForeground(NOTIFICATION_ID, createNotification("Đang phát...", currentUrl, true));
                } catch (IllegalStateException e) {
                    Log.e(TAG,"Error resuming playback", e);
                    stopPlayback(); // Stop if error occurs
                }
            } else {
                Log.w(TAG, "Playback already in progress or preparing for this URL.");
            }
        } else if (ACTION_STOP.equals(action)) {
            Log.d(TAG, "Received STOP action.");
            stopPlayback();
            stopSelf(); // Dừng service
        } else if (audioUrl == null && ACTION_PLAY.equals(action)) {
            Log.e(TAG, "Received PLAY action without audio URL.");
            // sendLogUpdate("Lỗi: Yêu cầu phát nhưng thiếu URL audio."); // Removed
            stopSelf();
        }


        return START_NOT_STICKY; // Không tự động restart service nếu bị kill
    }

    private void initializeAndPlay(String url) {
        // releaseMediaPlayer(); // Đảm bảo player cũ được giải phóng // Lỗi ở đây trong code gốc của user
        stopPlayback(); // Sửa thành stopPlayback()
        isPreparing = true;
        // sendLogUpdate("Bắt đầu khởi tạo MediaPlayer cho phát nhạc nền."); // Removed
        Log.i(TAG, "Initializing MediaPlayer for background playback.");

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
        );
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire(30*60*1000L /*30 minutes timeout*/); // Giữ CPU chạy khi chuẩn bị/phát
                Log.d(TAG, "WakeLock acquired.");
            } catch (Exception e) { // Catch generic exception for safety
                Log.e(TAG, "Error acquiring WakeLock", e);
            }
        }

        try {
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnErrorListener(this);
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.prepareAsync(); // Chuẩn bị bất đồng bộ
            // sendLogUpdate("MediaPlayer (nền) đang chuẩn bị..."); // Removed
            Log.i(TAG, "MediaPlayer (background) preparing...");
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException e) {
            Log.e(TAG, "Error setting data source or preparing MediaPlayer", e);
            // sendLogUpdate("Lỗi khi chuẩn bị MediaPlayer (nền): " + e.getMessage()); // Removed
            stopPlayback(); // Dừng nếu có lỗi ngay
            stopSelf();
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        Log.i(TAG, "MediaPlayer (background) prepared.");
        isPreparing = false;
        // sendLogUpdate("MediaPlayer (nền) đã sẵn sàng, bắt đầu phát."); // Removed
        try {
            mp.start();
            // Cập nhật notification để hiển thị trạng thái "Đang phát" và nút Stop
            startForeground(NOTIFICATION_ID, createNotification("Đang phát...", currentUrl, true));
            Log.i(TAG, "MediaPlayer (background) playback started.");
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error starting playback after prepare", e);
            // sendLogUpdate("Lỗi khi bắt đầu phát nhạc nền: " + e.getMessage()); // Removed
            stopPlayback();
            stopSelf();
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer (background) Error: what=" + what + ", extra=" + extra);
        isPreparing = false;
        String errorMsg = getMediaPlayerErrorString(what, extra); // Sử dụng hàm helper
        // sendLogUpdate("Lỗi MediaPlayer (nền): " + errorMsg); // Removed
        Toast.makeText(this, "Lỗi phát audio nền: " + errorMsg, Toast.LENGTH_LONG).show(); // Hiển thị lỗi cho người dùng
        stopPlayback();
        stopSelf();
        return true; // Lỗi đã được xử lý
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.i(TAG, "MediaPlayer (background) playback completed.");
        isPreparing = false;
        // sendLogUpdate("Phát audio nền hoàn tất."); // Removed
        stopPlayback();
        stopSelf(); // Tự động dừng service khi phát xong
    }

    private void stopPlayback() {
        Log.d(TAG, "Stopping playback and releasing resources.");
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping/releasing media player", e);
            } finally {
                mediaPlayer = null; // Quan trọng: đặt lại là null
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                Log.d(TAG, "WakeLock released.");
            } catch (Exception e) { // Catch generic exception
                Log.e(TAG, "Error releasing WakeLock", e);
            }
        }
        currentUrl = null; // Reset URL
        isPreparing = false;
        stopForeground(true); // Gỡ bỏ foreground notification
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopPlayback(); // Đảm bảo mọi thứ được giải phóng
        // sendLogUpdate("AudioPlaybackService đã dừng."); // Removed
        Log.i(TAG, "AudioPlaybackService destroyed.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Không dùng binding
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Kênh phát Audio nền";
            String description = "Thông báo cho việc phát audio trong nền";
            int importance = NotificationManager.IMPORTANCE_LOW; // Giảm độ ưu tiên để đỡ làm phiền
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            } else {
                Log.e(TAG, "Failed to get NotificationManager");
            }
        }
    }

    private Notification createNotification(String statusText, String contentUrl, boolean addStopAction) {
        Intent notificationIntent = new Intent(this, MainActivity.class); // Bấm vào noti vẫn mở MainActivity
        // Đưa URL vào intent để MainActivity có thể lấy nếu cần
        if (contentUrl != null) {
            notificationIntent.putExtra(MyFirebaseMessagingService.EXTRA_AUDIO_URL, contentUrl);
        }
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Đang phát Audio cảnh báo")
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_launcher_foreground) // Thay icon phù hợp (vd: icon loa)
                .setContentIntent(pendingIntent)
                .setOngoing(true); // Thông báo không thể hủy trừ khi service dừng

        if (addStopAction) {
            Intent stopIntent = new Intent(this, AudioPlaybackService.class);
            stopIntent.setAction(ACTION_STOP);
            // Sử dụng request code khác 0 để tránh xung đột với pendingIntent của notification content
            PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // Use a standard stop icon if available, otherwise keep the placeholder
            int stopIcon = android.R.drawable.ic_media_pause; // Or ic_media_stop
            builder.addAction(stopIcon, "Dừng", stopPendingIntent); // Thêm nút Stop
        }

        return builder.build();
    }

    // Gửi log về MainActivity nếu cần - Removed
    /*
    private void sendLogUpdate(String message) {
        Intent intent = new Intent(MyFirebaseMessagingService.ACTION_UPDATE_LOG); // Dùng action giống FCM Service
        intent.putExtra(MyFirebaseMessagingService.EXTRA_LOG_MESSAGE, "[AudioSvc] " + message); // Thêm prefix để phân biệt
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    */

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
            case -2147483648: extraError = "MEDIA_ERROR_SYSTEM"; break; // Common system error code
            default: extraError = "Extra code: " + extra;
        }
        return whatError + " (" + extraError + ")";
    }
}
