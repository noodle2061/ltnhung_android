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

    // Actions và Extras cho việc gửi token
    // public static final String ACTION_UPDATE_LOG = "com.example.nhandientienghet.UPDATE_LOG"; // Removed
    // public static final String EXTRA_LOG_MESSAGE = "extra_log_message"; // Removed
    public static final String ACTION_UPDATE_TOKEN = "com.example.nhandientienghet.UPDATE_TOKEN";
    public static final String EXTRA_FCM_TOKEN = "extra_fcm_token";
    public static final String PREF_LAST_TOKEN = "last_fcm_token";

    // Key cho audio URL và action mới cho MainActivity
    public static final String EXTRA_AUDIO_URL = "extra_audio_url"; // Đổi tên để thống nhất
    private static final String DATA_KEY_AUDIO_URL = "audio_url";
    public static final String ACTION_PLAY_AUDIO_NOW = "com.example.nhandientienghet.PLAY_AUDIO_NOW"; // Action mới


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        Log.d(TAG, "From: " + remoteMessage.getFrom());
        // sendLogUpdate("Nhận được tin nhắn từ: " + remoteMessage.getFrom()); // Removed

        String notificationTitle = null;
        String notificationBody = null;
        String audioUrl = null;

        // Ưu tiên lấy title/body từ notification payload nếu có
        if (remoteMessage.getNotification() != null) {
            notificationTitle = remoteMessage.getNotification().getTitle();
            notificationBody = remoteMessage.getNotification().getBody();
            Log.d(TAG, "Message Notification Title: " + notificationTitle);
            Log.d(TAG, "Message Notification Body: " + notificationBody);
            // sendLogUpdate("Payload thông báo: Tiêu đề='" + notificationTitle + "', Nội dung='" + notificationBody + "'"); // Removed
        }

        // Kiểm tra data payload
        if (remoteMessage.getData().size() > 0) {
            Map<String, String> data = remoteMessage.getData();
            Log.d(TAG, "Message data payload: " + data);
            // sendLogUpdate("Payload dữ liệu: " + data.toString()); // Removed

            // Ghi đè title/body nếu có trong data payload
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
                // sendLogUpdate("Đã tìm thấy Audio URL: " + audioUrl); // Removed

                // --- KIỂM TRA TRẠNG THÁI APP VÀ XỬ LÝ AUDIO ---
                if (MainActivity.isActivityVisible) {
                    // App đang ở foreground -> Gửi broadcast đến MainActivity
                    Log.i(TAG, "MainActivity is visible. Sending broadcast to play audio.");
                    Intent playIntent = new Intent(ACTION_PLAY_AUDIO_NOW);
                    playIntent.putExtra(EXTRA_AUDIO_URL, audioUrl);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(playIntent);
                    // Vẫn hiển thị notification thông thường để người dùng biết
                    sendNotification(notificationTitle, notificationBody, audioUrl, true); // true = thêm PendingIntent mở Activity
                } else {
                    // App đang ở background -> Khởi động AudioPlaybackService
                    Log.i(TAG, "MainActivity is not visible. Starting AudioPlaybackService.");
                    Intent playbackServiceIntent = new Intent(this, AudioPlaybackService.class);
                    playbackServiceIntent.setAction(AudioPlaybackService.ACTION_PLAY);
                    playbackServiceIntent.putExtra(AudioPlaybackService.EXTRA_AUDIO_URL, audioUrl);
                    try {
                        // Cần quyền FOREGROUND_SERVICE cho Android P trở lên
                        // Cần startForegroundService cho Android O trở lên
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(playbackServiceIntent);
                        } else {
                            startService(playbackServiceIntent);
                        }
                        // Hiển thị notification gốc (có thể không cần nếu service kia đã hiển thị)
                        // Hoặc hiển thị 1 noti đơn giản báo đang phát nền
                        sendNotification(notificationTitle, notificationBody, audioUrl, true); // Vẫn cho phép mở app từ noti
                    } catch (SecurityException e) {
                        Log.e(TAG, "Missing FOREGROUND_SERVICE permission?", e);
                        // sendLogUpdate("Lỗi: Thiếu quyền FOREGROUND_SERVICE để phát nhạc nền."); // Removed
                        // Gửi notification lỗi hoặc chỉ log
                        sendNotification("Lỗi phát nhạc nền", "Thiếu quyền cần thiết.", null, false);
                    } catch (IllegalStateException e) {
                        // Lỗi này thường xảy ra trên Android 8+ nếu không gọi startForegroundService
                        Log.e(TAG, "Failed to start service in background (requires startForegroundService on O+)", e);
                        // sendLogUpdate("Lỗi: Không thể bắt đầu service phát nhạc nền."); // Removed
                        sendNotification("Lỗi phát nhạc nền", "Không thể khởi động dịch vụ.", null, false);
                    }
                }
                // ---------------------------------------------

            } else {
                Log.w(TAG, "Data payload does not contain key: " + DATA_KEY_AUDIO_URL);
                // sendLogUpdate("Không tìm thấy Audio URL trong dữ liệu."); // Removed
                // Nếu không có audio URL, chỉ hiển thị notification thông thường
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
        // sendLogUpdate("FCM Token mới: " + token.substring(0, 15) + "..."); // Removed

        saveTokenToPrefs(token);
        sendRegistrationToServer(token);
        sendTokenUpdate(token);
    }

    // Removed sendLogUpdate method
    /*
    private void sendLogUpdate(String message) {
        Intent intent = new Intent(ACTION_UPDATE_LOG);
        intent.putExtra(EXTRA_LOG_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    */

    private void sendTokenUpdate(String token) {
        Intent intent = new Intent(ACTION_UPDATE_TOKEN);
        intent.putExtra(EXTRA_FCM_TOKEN, token);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void saveTokenToPrefs(String token) {
        // Assuming MainActivity.PREFS_NAME exists or replace with your prefs name
        String prefsName = "TcpClientPrefs"; // Use a consistent name
        SharedPreferences prefs = getSharedPreferences(prefsName, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_LAST_TOKEN, token);
        editor.apply();
        Log.d(TAG, "Token saved to SharedPreferences.");
    }


    private void sendRegistrationToServer(final String token) {
        // sendLogUpdate("Đang chuẩn bị gửi token lên server..."); // Removed
        Log.i(TAG, "Preparing to send token to server...");
        executorService.execute(() -> {
            String serverUrlString = "http://192.168.56.103:5000/register_token"; // <<< Kiểm tra lại IP nếu cần
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
                        inputStream.close(); // Đảm bảo đóng stream
                    }
                }

                if(success) {
                    // sendLogUpdate("Gửi token lên server ("+ serverUrlString +") thành công. Phản hồi: " + serverResponseMessage); // Removed
                    Log.i(TAG, "Token sent to server ("+ serverUrlString +") successfully. Response: " + serverResponseMessage);
                } else {
                    // sendLogUpdate("Gửi token lên server ("+ serverUrlString +") thất bại. Mã lỗi: " + responseCode + ". Phản hồi: " + serverResponseMessage); // Removed
                    Log.e(TAG, "Failed to send token to server ("+ serverUrlString +"). Code: " + responseCode + ". Response: " + serverResponseMessage);
                }

            } catch (MalformedURLException e) {
                Log.e(TAG, "Error creating URL: " + serverUrlString, e);
                // sendLogUpdate("Lỗi URL khi gửi token: " + e.getMessage()); // Removed
            } catch (IOException e) {
                if (e instanceof java.net.ConnectException) {
                    // sendLogUpdate("Lỗi kết nối đến server ("+ serverUrlString +"): " + e.getMessage() + ". Kiểm tra IP/Port và server có đang chạy không."); // Removed
                    Log.e(TAG, "Connection error sending token to server ("+ serverUrlString +"): " + e.getMessage() + ". Check IP/Port and if server is running.");
                } else {
                    // sendLogUpdate("Lỗi IO khi gửi token: " + e.getMessage()); // Removed
                    Log.e(TAG, "IO error sending token: " + e.getMessage());
                }
                Log.e(TAG, "Error sending token to server: " + e.getMessage(), e);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating JSON for token", e);
                // sendLogUpdate("Lỗi tạo JSON khi gửi token."); // Removed
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        });
    }

    // Sửa hàm này để nhận thêm cờ quyết định có tạo PendingIntent mở Activity không
    private void sendNotification(String messageTitle, String messageBody, String audioUrl, boolean createActivityIntent) {
        PendingIntent pendingIntent = null;
        if (createActivityIntent) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (audioUrl != null && !audioUrl.isEmpty()) {
                intent.putExtra(EXTRA_AUDIO_URL, audioUrl);
            }
            pendingIntent = PendingIntent.getActivity(this, 0 /* Request code */, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        String channelId = getString(R.string.default_notification_channel_id);
        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(R.drawable.ic_launcher_foreground) // Consider using a specific notification icon
                        .setContentTitle(messageTitle != null ? messageTitle : getString(R.string.app_name))
                        .setContentText(messageBody)
                        .setAutoCancel(true) // Tự hủy khi bấm vào
                        .setSound(defaultSoundUri)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);

        if (pendingIntent != null) {
            notificationBuilder.setContentIntent(pendingIntent);
        }

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.default_notification_channel_name);
            String description = getString(R.string.default_notification_channel_description);
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(channelId, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        int notificationId = (int) System.currentTimeMillis(); // Use a unique ID
        if (notificationManager != null) {
            if (messageTitle != null || messageBody != null) { // Only notify if there is content
                notificationManager.notify(notificationId, notificationBuilder.build());
                // sendLogUpdate("Đã hiển thị thông báo trên thiết bị."); // Removed
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
