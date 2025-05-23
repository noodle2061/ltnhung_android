package com.example.nhandientienghet; // Đảm bảo đúng package name

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "MyFirebaseMsgService";
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // Actions và Extras cho việc gửi token và audio
    public static final String ACTION_UPDATE_TOKEN = "com.example.nhandientienghet.UPDATE_TOKEN";
    public static final String EXTRA_FCM_TOKEN = "extra_fcm_token";
    public static final String PREF_LAST_TOKEN = "last_fcm_token";

    public static final String EXTRA_AUDIO_URL = "extra_audio_url";
    private static final String DATA_KEY_AUDIO_URL = "audio_url";
    public static final String ACTION_PLAY_AUDIO_NOW = "com.example.nhandientienghet.PLAY_AUDIO_NOW";

    // <<< THÊM: Key cho SharedPreferences >>>
    public static final String PREFS_NAME = "NhanDienTiengHetPrefs"; // Đặt tên thống nhất
    public static final String PREF_LAST_AUDIO_URL = "last_audio_url";


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        Log.d(TAG, "From: " + remoteMessage.getFrom());

        String notificationTitle = null;
        String notificationBody = null;
        String audioUrl = null;

        // Lấy title/body từ notification payload
        if (remoteMessage.getNotification() != null) {
            notificationTitle = remoteMessage.getNotification().getTitle();
            notificationBody = remoteMessage.getNotification().getBody();
            Log.d(TAG, "Message Notification Title: " + notificationTitle);
            Log.d(TAG, "Message Notification Body: " + notificationBody);
        }

        // Kiểm tra data payload
        if (remoteMessage.getData().size() > 0) {
            Map<String, String> data = remoteMessage.getData();
            Log.d(TAG, "Message data payload: " + data);

            // Ghi đè title/body nếu có trong data
            if (data.containsKey("title") && notificationTitle == null) {
                notificationTitle = data.get("title");
            }
            if (data.containsKey("body") && notificationBody == null) {
                notificationBody = data.get("body");
            }

            // LẤY AUDIO URL TỪ DATA PAYLOAD
            if (data.containsKey(DATA_KEY_AUDIO_URL)) {
                audioUrl = data.get(DATA_KEY_AUDIO_URL);
                Log.i(TAG, "Audio URL found in data payload: " + audioUrl);

                // <<< THAY ĐỔI: Luôn lưu URL mới nhất vào SharedPreferences >>>
                if (audioUrl != null && !audioUrl.isEmpty()) {
                    saveLastAudioUrlToPrefs(audioUrl);
                }
                // <<< KẾT THÚC THAY ĐỔI >>>

                // --- KIỂM TRA TRẠNG THÁI APP VÀ XỬ LÝ AUDIO ---
                if (MainActivity.isActivityVisible) {
                    // App đang ở foreground -> Gửi broadcast đến MainActivity (để cập nhật ngay lập tức)
                    Log.i(TAG, "MainActivity is visible. Sending broadcast to play audio.");
                    Intent playIntent = new Intent(ACTION_PLAY_AUDIO_NOW);
                    playIntent.putExtra(EXTRA_AUDIO_URL, audioUrl);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(playIntent);
                    // Vẫn hiển thị notification gốc (có thể không cần nếu không muốn duplicate)
                    // sendNotification(notificationTitle, notificationBody, audioUrl, true);
                } else {
                    // App đang ở background -> Khởi động AudioPlaybackService
                    Log.i(TAG, "MainActivity is not visible. Starting AudioPlaybackService.");
                    Intent playbackServiceIntent = new Intent(this, AudioPlaybackService.class);
                    playbackServiceIntent.setAction(AudioPlaybackService.ACTION_PLAY);
                    playbackServiceIntent.putExtra(AudioPlaybackService.EXTRA_AUDIO_URL, audioUrl);
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(playbackServiceIntent);
                        } else {
                            startService(playbackServiceIntent);
                        }
                        // Hiển thị notification để người dùng biết và có thể mở app
                        sendNotification(notificationTitle, notificationBody, audioUrl, true);
                    } catch (SecurityException | IllegalStateException e) {
                        Log.e(TAG, "Error starting background service", e);
                        sendNotification("Lỗi phát nhạc nền", "Không thể khởi động dịch vụ.", null, false);
                    }
                }
                // ---------------------------------------------

            } else {
                Log.w(TAG, "Data payload does not contain key: " + DATA_KEY_AUDIO_URL);
                // Nếu không có audio URL, chỉ hiển thị notification thông thường nếu có title/body
                if (notificationTitle != null && notificationBody != null) {
                    sendNotification(notificationTitle, notificationBody, null, true);
                }
            }
        } else {
            // Không có data payload, chỉ có notification payload
            if (notificationTitle != null && notificationBody != null) {
                sendNotification(notificationTitle, notificationBody, null, true);
            }
        }
    }


    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "Refreshed token: " + token);
        saveTokenToPrefs(token); // Lưu cả token vào prefs nếu muốn
        sendRegistrationToServer(token);
        // Gửi broadcast nếu MainActivity cần biết token mới ngay lập tức (tùy chọn)
        // sendTokenUpdate(token);
    }

    // Hàm lưu token vào SharedPreferences (có thể dùng chung prefsName)
    private void saveTokenToPrefs(String token) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_LAST_TOKEN, token);
        editor.apply();
        Log.d(TAG, "Token saved to SharedPreferences.");
    }

    // <<< THÊM: Hàm lưu URL audio cuối cùng vào SharedPreferences >>>
    private void saveLastAudioUrlToPrefs(String url) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_LAST_AUDIO_URL, url);
        editor.apply();
        Log.i(TAG, "Last audio URL saved to SharedPreferences: " + url);
    }


    private void sendRegistrationToServer(final String token) {
        // ... (Giữ nguyên hàm gửi token lên server)
        Log.i(TAG, "Preparing to send token to server...");
        executorService.execute(() -> {
            String serverUrlString = "http://192.168.209.103:5000/register_token"; // <<< Kiểm tra lại IP nếu cần
            HttpURLConnection urlConnection = null;
            boolean success = false;
            String serverResponseMessage = "N/A";
            try {
                URL url = new URL(serverUrlString);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("POST");
                urlConnection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                urlConnection.setRequestProperty("Accept", "application/json");
                urlConnection.setDoOutput(true);
                urlConnection.setDoInput(true);
                urlConnection.setConnectTimeout(15000);
                urlConnection.setReadTimeout(10000);

                JSONObject jsonParam = new JSONObject();
                jsonParam.put("token", token);
                String jsonInputString = jsonParam.toString();

                try (OutputStream os = urlConnection.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = urlConnection.getResponseCode();
                InputStream inputStream;
                if (responseCode >= 200 && responseCode < 300) {
                    inputStream = urlConnection.getInputStream();
                    success = true;
                } else {
                    inputStream = urlConnection.getErrorStream();
                }

                if (inputStream != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line.trim());
                        }
                        serverResponseMessage = response.toString();
                    } finally {
                        inputStream.close();
                    }
                }

                if(success) {
                    Log.i(TAG, "Token sent to server ("+ serverUrlString +") successfully. Response: " + serverResponseMessage);
                } else {
                    Log.e(TAG, "Failed to send token to server ("+ serverUrlString +"). Code: " + responseCode + ". Response: " + serverResponseMessage);
                }

            } catch (MalformedURLException e) {
                Log.e(TAG, "Error creating URL: " + serverUrlString, e);
            } catch (IOException e) {
                if (e instanceof java.net.ConnectException) {
                    Log.e(TAG, "Connection error sending token to server ("+ serverUrlString +"): " + e.getMessage() + ". Check IP/Port and if server is running.");
                } else {
                    Log.e(TAG, "IO error sending token: " + e.getMessage());
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error creating JSON for token", e);
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        });
    }

    // Gửi notification (Giữ nguyên, đảm bảo intent có chứa audioUrl nếu có)
    private void sendNotification(String messageTitle, String messageBody, String audioUrl, boolean createActivityIntent) {
        // ... (Giữ nguyên hàm sendNotification)
        PendingIntent pendingIntent = null;
        if (createActivityIntent) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (audioUrl != null && !audioUrl.isEmpty()) {
                intent.putExtra(EXTRA_AUDIO_URL, audioUrl); // Đảm bảo gửi URL qua intent
            }
            pendingIntent = PendingIntent.getActivity(this, 0 /* Request code */, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        String channelId = getString(R.string.default_notification_channel_id);
        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle(messageTitle != null ? messageTitle : getString(R.string.app_name))
                        .setContentText(messageBody)
                        .setAutoCancel(true)
                        .setSound(defaultSoundUri)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);

        if (pendingIntent != null) {
            notificationBuilder.setContentIntent(pendingIntent);
        }

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.default_notification_channel_name);
            String description = getString(R.string.default_notification_channel_description);
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(channelId, name, importance);
            channel.setDescription(description);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        int notificationId = (int) System.currentTimeMillis();
        if (notificationManager != null) {
            if (messageTitle != null || messageBody != null) {
                notificationManager.notify(notificationId, notificationBuilder.build());
                Log.i(TAG, "Notification displayed on device.");
            }
        } else {
            Log.e(TAG, "NotificationManager is null, cannot display notification.");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            Log.d(TAG, "ExecutorService shut down.");
        }
    }
}