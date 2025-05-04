package com.example.nhandientienghet;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler; // Import Handler
import android.os.Looper; // Import Looper
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Implement interface đã đổi tên
public class HistoryActivity extends AppCompatActivity implements HistoryAdapter.OnItemInteractionListener {

    private static final String TAG = "HistoryActivity";
    private static final String SERVER_BASE_URL = "http://192.168.56.103:5000"; // <<< THAY THẾ IP/PORT CỦA BẠN
    private static final String HISTORY_API_URL = SERVER_BASE_URL + "/alert_history";
    private static final String GET_AUDIO_URL_API = SERVER_BASE_URL + "/get_audio_url";

    private RecyclerView recyclerViewHistory;
    private HistoryAdapter historyAdapter;
    private List<HistoryItem> historyItemList;
    private ProgressBar progressBarHistory;
    private TextView tvHistoryStatus;
    private RequestQueue requestQueue;

    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    // --- State Management for Playback ---
    private int currentPlayingPosition = -1; // Vị trí item đang phát
    private boolean isAudioPlaying = false; // Flag trạng thái phát
    private boolean isAudioPrepared = false; // Flag trạng thái chuẩn bị
    private String currentPlayingS3Key = null; // S3 key của item đang phát
    private Handler progressUpdateHandler; // Handler để cập nhật progress bar
    private Runnable progressUpdateRunnable; // Runnable cho handler

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_history);

        recyclerViewHistory = findViewById(R.id.recyclerViewHistory);
        progressBarHistory = findViewById(R.id.progressBarHistory);
        tvHistoryStatus = findViewById(R.id.tvHistoryStatus);

        recyclerViewHistory.setLayoutManager(new LinearLayoutManager(this));
        historyItemList = new ArrayList<>();
        // Truyền 'this' vì Activity implement OnItemInteractionListener đã cập nhật
        historyAdapter = new HistoryAdapter(this, historyItemList, this);
        recyclerViewHistory.setAdapter(historyAdapter);

        requestQueue = Volley.newRequestQueue(this);

        // Khởi tạo WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NhanDienTiengHet::HistoryAudioWakeLock");
            wakeLock.setReferenceCounted(false);
        }

        // Khởi tạo Handler và Runnable cho progress bar
        progressUpdateHandler = new Handler(Looper.getMainLooper());
        progressUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isAudioPlaying && isAudioPrepared) {
                    try {
                        int currentPosition = mediaPlayer.getCurrentPosition();
                        int duration = mediaPlayer.getDuration();
                        if (duration > 0) {
                            int progress = (int) (((float) currentPosition / duration) * 100);
                            // Cập nhật progress trong adapter
                            historyAdapter.updateProgress(progress);
                        }
                        // Lập lịch chạy lại sau 500ms
                        progressUpdateHandler.postDelayed(this, 500);
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "IllegalStateException while getting MediaPlayer position/duration", e);
                        stopProgressUpdater(); // Dừng cập nhật nếu có lỗi
                    }
                } else {
                    stopProgressUpdater(); // Dừng nếu không còn phát
                }
            }
        };


        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        fetchHistoryData();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (requestQueue != null) {
            requestQueue.cancelAll(TAG);
        }
        stopProgressUpdater(); // Dừng handler
        releaseMediaPlayer(); // Giải phóng MediaPlayer
        releaseWakeLock(); // Giải phóng WakeLock
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Tạm dừng audio nếu đang phát khi activity pause
        if (mediaPlayer != null && isAudioPlaying) {
            try {
                mediaPlayer.pause();
                isAudioPlaying = false; // Cập nhật trạng thái
                stopProgressUpdater(); // Dừng cập nhật progress
                // Cập nhật UI của item đang phát
                historyAdapter.setPlayingState(currentPlayingPosition, false);
                Toast.makeText(this, R.string.audio_paused_history, Toast.LENGTH_SHORT).show();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error pausing MediaPlayer onPause", e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Không tự động resume audio khi quay lại activity, để người dùng tự nhấn play
    }

    private void fetchHistoryData() {
        // Giữ nguyên hàm fetchHistoryData
        Log.d(TAG, "Fetching history data from: " + HISTORY_API_URL);
        progressBarHistory.setVisibility(View.VISIBLE);
        tvHistoryStatus.setVisibility(View.GONE);
        recyclerViewHistory.setVisibility(View.GONE);

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.GET, HISTORY_API_URL, null,
                response -> {
                    Log.d(TAG, "History response received: " + response.toString());
                    progressBarHistory.setVisibility(View.GONE);
                    try {
                        String status = response.getString("status");
                        if ("success".equals(status)) {
                            JSONArray historyArray = response.getJSONArray("history");
                            if (historyArray.length() > 0) {
                                historyItemList.clear();
                                for (int i = 0; i < historyArray.length(); i++) {
                                    JSONObject itemObject = historyArray.getJSONObject(i);
                                    String id = itemObject.getString("id");
                                    String timestamp = itemObject.getString("timestamp");
                                    String clientIp = itemObject.getString("client_ip");
                                    String s3Key = itemObject.optString("s3_key", null);
                                    HistoryItem item = new HistoryItem(id, timestamp, clientIp, s3Key);
                                    historyItemList.add(item);
                                }
                                historyAdapter.notifyDataSetChanged();
                                recyclerViewHistory.setVisibility(View.VISIBLE);
                            } else {
                                tvHistoryStatus.setText(R.string.history_empty);
                                tvHistoryStatus.setVisibility(View.VISIBLE);
                                recyclerViewHistory.setVisibility(View.GONE);
                            }
                        } else {
                            String message = response.optString("message", "Unknown server success error");
                            Log.e(TAG, "Server returned success=false: " + message);
                            showError(getString(R.string.history_error_server) + ": " + message);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing history JSON response", e);
                        showError(getString(R.string.history_error_parsing));
                    }
                },
                error -> {
                    Log.e(TAG, "Volley error fetching history: " + error.toString());
                    progressBarHistory.setVisibility(View.GONE);
                    showError(getString(R.string.history_error_network));
                });

        jsonObjectRequest.setTag(TAG);
        requestQueue.add(jsonObjectRequest);
    }

    private void showError(String message) {
        // Giữ nguyên hàm showError
        tvHistoryStatus.setText(message);
        tvHistoryStatus.setVisibility(View.VISIBLE);
        recyclerViewHistory.setVisibility(View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // --- Implement phương thức từ HistoryAdapter.OnItemInteractionListener ---

    @Override
    public void onPlayPauseClick(int position, HistoryItem item) {
        Log.d(TAG, "onPlayPauseClick - Position: " + position + ", Current Playing: " + currentPlayingPosition);
        if (item.getS3Key() == null) {
            Toast.makeText(this, R.string.no_audio_for_item, Toast.LENGTH_SHORT).show();
            return;
        }

        if (position == currentPlayingPosition) {
            // Click vào item đang phát/tạm dừng
            if (mediaPlayer != null && isAudioPrepared) {
                if (isAudioPlaying) {
                    // Đang phát -> Tạm dừng
                    try {
                        mediaPlayer.pause();
                        isAudioPlaying = false;
                        stopProgressUpdater();
                        historyAdapter.setPlayingState(position, false); // Cập nhật UI
                        Log.i(TAG, "Audio paused by user.");
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error pausing MediaPlayer", e);
                        resetPlaybackState(); // Reset nếu lỗi
                    }
                } else {
                    // Đang tạm dừng -> Tiếp tục phát
                    try {
                        mediaPlayer.start();
                        isAudioPlaying = true;
                        startProgressUpdater(); // Bắt đầu lại cập nhật progress
                        historyAdapter.setPlayingState(position, true); // Cập nhật UI
                        Log.i(TAG, "Audio resumed by user.");
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error resuming MediaPlayer", e);
                        resetPlaybackState(); // Reset nếu lỗi
                    }
                }
            } else {
                // Trường hợp mediaPlayer null hoặc chưa prepared (có thể do lỗi trước đó)
                Log.w(TAG, "Play/Pause clicked on current item, but MediaPlayer is not ready. Re-fetching.");
                fetchAndPlayAudio(position, item.getS3Key()); // Thử fetch và play lại
            }
        } else {
            // Click vào item mới -> Dừng item cũ (nếu có) và bắt đầu phát item mới
            Log.i(TAG, "Playing new item at position: " + position);
            resetPlaybackState(); // Dừng và reset trạng thái cũ
            fetchAndPlayAudio(position, item.getS3Key()); // Fetch và play item mới
        }
    }


    // --- Các hàm xử lý phát audio ---

    private void fetchAndPlayAudio(int position, String s3Key) {
        // resetPlaybackState(); // Đã gọi ở onPlayPauseClick nếu cần
        currentPlayingPosition = position; // Đặt vị trí đang xử lý
        currentPlayingS3Key = s3Key;
        isAudioPlaying = false; // Chưa phát
        isAudioPrepared = false; // Chưa chuẩn bị
        historyAdapter.setPlayingState(position, false); // Cập nhật UI (hiển thị loading/play icon)

        Toast.makeText(this, R.string.audio_fetching_url, Toast.LENGTH_SHORT).show();

        String apiUrl = GET_AUDIO_URL_API + "?s3_key=" + Uri.encode(s3Key);
        Log.d(TAG, "Fetching S3 URL from: " + apiUrl);

        StringRequest stringRequest = new StringRequest(Request.Method.GET, apiUrl,
                response -> {
                    Log.d(TAG, "S3 URL response: " + response);
                    try {
                        JSONObject jsonResponse = new JSONObject(response);
                        String status = jsonResponse.getString("status");
                        if ("success".equals(status)) {
                            String presignedUrl = jsonResponse.getString("url");
                            Log.i(TAG, "Got pre-signed URL: " + presignedUrl);
                            // Chỉ play nếu item này vẫn là item đang được chọn
                            if (position == currentPlayingPosition) {
                                playAudioFromUrl(presignedUrl);
                            } else {
                                Log.w(TAG, "URL received for position " + position + " but user selected another item (" + currentPlayingPosition + "). Ignoring.");
                            }
                        } else {
                            String message = jsonResponse.optString("message", "Failed to get URL");
                            Log.e(TAG, "Server error getting S3 URL: " + message);
                            Toast.makeText(HistoryActivity.this, getString(R.string.audio_error_url) + ": " + message, Toast.LENGTH_LONG).show();
                            resetPlaybackState(); // Reset nếu lỗi lấy URL
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing S3 URL JSON response", e);
                        Toast.makeText(HistoryActivity.this, R.string.audio_error_url_parsing, Toast.LENGTH_LONG).show();
                        resetPlaybackState(); // Reset nếu lỗi parse
                    }
                },
                error -> {
                    Log.e(TAG, "Volley error fetching S3 URL: " + error.toString());
                    Toast.makeText(HistoryActivity.this, R.string.audio_error_url_network, Toast.LENGTH_LONG).show();
                    resetPlaybackState(); // Reset nếu lỗi mạng
                });

        stringRequest.setTag(TAG);
        requestQueue.add(stringRequest);
    }

    private void playAudioFromUrl(String url) {
        releaseMediaPlayer(); // Đảm bảo player cũ đã được giải phóng
        acquireWakeLock();

        Toast.makeText(this, R.string.audio_preparing, Toast.LENGTH_SHORT).show();
        Log.i(TAG, "Initializing MediaPlayer for URL: " + url);

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
        );

        try {
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(mp -> {
                Log.i(TAG, "MediaPlayer prepared, starting playback for position: " + currentPlayingPosition);
                isAudioPrepared = true;
                // Chỉ bắt đầu phát nếu item này vẫn là item đang được chọn
                if (mp != null && currentPlayingPosition != -1) {
                    try {
                        mp.start();
                        isAudioPlaying = true;
                        historyAdapter.setPlayingState(currentPlayingPosition, true); // Cập nhật UI -> pause icon
                        startProgressUpdater(); // Bắt đầu cập nhật progress bar
                        Toast.makeText(HistoryActivity.this, R.string.audio_playing, Toast.LENGTH_SHORT).show();
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error starting playback after prepare", e);
                        Toast.makeText(HistoryActivity.this, R.string.audio_error_playback, Toast.LENGTH_LONG).show();
                        resetPlaybackState();
                    }
                } else {
                    Log.w(TAG, "MediaPlayer prepared, but currentPlayingPosition is invalid or player is null. Not starting.");
                    resetPlaybackState();
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.i(TAG, "MediaPlayer playback completed for position: " + currentPlayingPosition);
                Toast.makeText(HistoryActivity.this, R.string.audio_completed, Toast.LENGTH_SHORT).show();
                resetPlaybackState(); // Reset trạng thái khi hoàn thành
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer Error: what=" + what + ", extra=" + extra + " for position: " + currentPlayingPosition);
                Toast.makeText(HistoryActivity.this, R.string.audio_error_playback, Toast.LENGTH_LONG).show();
                resetPlaybackState(); // Reset trạng thái khi có lỗi
                return true; // Đã xử lý lỗi
            });

            mediaPlayer.prepareAsync();

        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException e) {
            Log.e(TAG, "Error setting data source or preparing MediaPlayer", e);
            Toast.makeText(this, R.string.audio_error_preparing, Toast.LENGTH_LONG).show();
            resetPlaybackState(); // Reset nếu lỗi chuẩn bị
        }
    }

    // Hàm reset trạng thái playback
    private void resetPlaybackState() {
        Log.d(TAG, "Resetting playback state. Previous playing position: " + currentPlayingPosition);
        releaseMediaPlayer(); // Dừng và giải phóng player
        stopProgressUpdater(); // Dừng cập nhật progress
        releaseWakeLock(); // Giải phóng wakelock

        int previousPosition = currentPlayingPosition;
        currentPlayingPosition = -1;
        isAudioPlaying = false;
        isAudioPrepared = false;
        currentPlayingS3Key = null;

        // Cập nhật UI cho item cũ (nếu có) để reset về trạng thái mặc định
        if (previousPosition != -1) {
            historyAdapter.setPlayingState(previousPosition, false); // Đặt lại icon play
            historyAdapter.updateProgress(0); // Đặt lại progress bar
        }
    }


    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            Log.d(TAG, "Releasing MediaPlayer.");
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaPlayer", e);
            } finally {
                mediaPlayer = null;
                isAudioPlaying = false;
                isAudioPrepared = false;
            }
        }
    }

    // --- WakeLock Handling (Giữ nguyên) ---
    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire(10*60*1000L /*10 minutes timeout*/);
                Log.d(TAG, "WakeLock acquired for history audio.");
            } catch (Exception e) {
                Log.e(TAG, "Error acquiring WakeLock", e);
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                Log.d(TAG, "WakeLock released for history audio.");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing WakeLock", e);
            }
        }
    }

    // --- Progress Updater Handling ---
    private void startProgressUpdater() {
        stopProgressUpdater(); // Dừng cái cũ trước khi bắt đầu cái mới
        progressUpdateHandler.post(progressUpdateRunnable);
        Log.d(TAG, "Started progress updater.");
    }

    private void stopProgressUpdater() {
        progressUpdateHandler.removeCallbacks(progressUpdateRunnable);
        Log.d(TAG, "Stopped progress updater.");
    }
}
