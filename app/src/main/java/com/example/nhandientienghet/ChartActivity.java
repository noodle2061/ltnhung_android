package com.example.nhandientienghet;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class ChartActivity extends AppCompatActivity {

    private static final String TAG = "ChartActivity";
    private static final int MAX_VISIBLE_ENTRIES = 60; // Số điểm dữ liệu hiển thị trên biểu đồ
    private static final int MAX_DATA_POINTS = 300; // Giới hạn tổng số điểm dữ liệu trong bộ nhớ
    // URL đúng cho Firebase Realtime Database của bạn
    private static final String FIREBASE_DB_URL = "https://nhandientienghetapp-default-rtdb.asia-southeast1.firebasedatabase.app";

    private LineChart lineChart;
    private Button btnBack;
    private TextView tvChartStatus;

    private DatabaseReference audioLevelsRef;
    private ValueEventListener audioLevelListener;
    private String targetDeviceIp = null; // IP của thiết bị cần theo dõi (đã mã hóa)

    // Bỏ ArrayList<Entry> entries vì ta sẽ thao tác trực tiếp với LineDataSet
    private LineData lineData;
    private long startTimeMillis = System.currentTimeMillis(); // Thời gian bắt đầu để tính trục X

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chart);

        lineChart = findViewById(R.id.lineChart);
        btnBack = findViewById(R.id.btnBack);
        tvChartStatus = findViewById(R.id.tvChartStatus);

        btnBack.setOnClickListener(v -> finish()); // Sử dụng finish() để đóng Activity hiện tại

        setupChart();
        findTargetDeviceAndStartListening();
    }

    private void setupChart() {
        Log.d(TAG, "Setting up chart...");
        lineChart.setDrawGridBackground(false);
        lineChart.getDescription().setEnabled(true);
        Description description = new Description();
        description.setText(getString(R.string.chart_description));
        lineChart.setDescription(description);
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setPinchZoom(true);

        // Đặt text khi không có dữ liệu (để chắc chắn)
        lineChart.setNoDataText(getString(R.string.chart_waiting_for_data));

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f); // only intervals of 1 unit
        // Format trục X để hiển thị thời gian
        xAxis.setValueFormatter(new ValueFormatter() {
            private final SimpleDateFormat mFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            @Override
            public String getAxisLabel(float value, com.github.mikephil.charting.components.AxisBase axis) {
                // Hiển thị thời gian thực tế dựa trên startTimeMillis
                long millis = startTimeMillis + (long) (value * 1000);
                return mFormat.format(new Date(millis));
            }
        });


        YAxis leftAxis = lineChart.getAxisLeft();
        // *** BỎ ĐẶT MIN/MAX CỐ ĐỊNH ĐỂ BẬT AUTO-SCALING ***
        // leftAxis.setAxisMaximum(1.0f);
        // leftAxis.setAxisMinimum(0.0f);
        // ************************************************
        leftAxis.setDrawGridLines(true);
        // Có thể thêm dòng này để đảm bảo trục Y bắt đầu từ 0 nếu muốn, nhưng vẫn tự điều chỉnh max
        leftAxis.setAxisMinimum(0f); // Đảm bảo trục Y luôn bắt đầu từ 0


        lineChart.getAxisRight().setEnabled(false); // Tắt trục Y bên phải

        // Khởi tạo LineData rỗng ban đầu
        lineData = new LineData();
        lineChart.setData(lineData); // Set data rỗng cho chart
        lineChart.invalidate(); // Refresh chart ban đầu
        Log.d(TAG, "Chart setup complete.");
    }

    // Tìm IP của thiết bị đầu tiên có dữ liệu và bắt đầu lắng nghe
    private void findTargetDeviceAndStartListening() {
        tvChartStatus.setText(R.string.chart_waiting_for_data);
        tvChartStatus.setVisibility(View.VISIBLE);
        lineChart.clear(); // Xóa dữ liệu cũ nếu có

        // Sử dụng URL đúng khi lấy reference
        DatabaseReference rootRef = FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("audio_levels");

        rootRef.limitToFirst(1).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    // Lấy key (IP đã được thay thế dấu '.' bằng '-') của nút con đầu tiên
                    for (DataSnapshot childSnapshot : dataSnapshot.getChildren()) {
                        targetDeviceIp = childSnapshot.getKey(); // Đây là IP đã được mã hóa
                        if (targetDeviceIp != null) {
                            Log.i(TAG, "Found target device IP (encoded): " + targetDeviceIp);
                            startListeningForAudioLevels(targetDeviceIp);
                            return; // Chỉ lấy IP đầu tiên
                        }
                    }
                    // Trường hợp không tìm thấy key hợp lệ (dù snapshot tồn tại)
                    Log.e(TAG, "Snapshot exists but no valid child key found.");
                    tvChartStatus.setText(R.string.chart_no_data_source);
                    lineChart.setNoDataText(getString(R.string.chart_no_data_source)); // Cập nhật text trên chart
                    lineChart.invalidate(); // Vẽ lại chart để hiển thị text mới
                } else {
                    Log.w(TAG, "No device data found under /audio_levels in Firebase.");
                    tvChartStatus.setText(R.string.chart_no_data_source);
                    lineChart.setNoDataText(getString(R.string.chart_no_data_source)); // Cập nhật text trên chart
                    lineChart.invalidate(); // Vẽ lại chart để hiển thị text mới
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Firebase error finding target device: " + databaseError.getMessage());
                tvChartStatus.setText(R.string.chart_error_loading_data);
                lineChart.setNoDataText(getString(R.string.chart_error_loading_data)); // Cập nhật text trên chart
                lineChart.invalidate(); // Vẽ lại chart để hiển thị text mới
                Toast.makeText(ChartActivity.this, R.string.chart_error_loading_data, Toast.LENGTH_SHORT).show();
            }
        });
    }


    private void startListeningForAudioLevels(String encodedIp) {
        if (encodedIp == null) {
            Log.e(TAG, "Cannot start listening, encoded IP is null.");
            return;
        }
        // Sử dụng URL đúng khi lấy reference
        audioLevelsRef = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
                .getReference("audio_levels")
                .child(encodedIp)
                .child("latest");

        Log.i(TAG, "Starting to listen for data at: " + audioLevelsRef.toString());

        audioLevelListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    runOnUiThread(() -> { // Đảm bảo chạy trên UI thread
                        tvChartStatus.setVisibility(View.GONE); // Ẩn text trạng thái khi có dữ liệu
                        try {
                            // Lấy dữ liệu amplitude và timestamp
                            Double amplitude = dataSnapshot.child("amplitude").getValue(Double.class);

                            if (amplitude != null) {
                                float ampValue = amplitude.floatValue();
                                float timeElapsedSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000.0f;

                                Log.d(TAG, "New data received: Time=" + timeElapsedSeconds + "s, Amplitude=" + ampValue);
                                addEntry(timeElapsedSeconds, ampValue);
                            } else {
                                Log.w(TAG, "Amplitude data is null in snapshot.");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing data snapshot: " + e.getMessage(), e);
                        }
                    }); // Kết thúc runOnUiThread
                } else {
                    Log.w(TAG, "Data snapshot for 'latest' does not exist or is null.");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Firebase listener cancelled: " + databaseError.getMessage());
                runOnUiThread(() -> { // Đảm bảo chạy trên UI thread
                    tvChartStatus.setText(R.string.chart_error_loading_data);
                    tvChartStatus.setVisibility(View.VISIBLE);
                    lineChart.setNoDataText(getString(R.string.chart_error_loading_data)); // Cập nhật text trên chart
                    lineChart.invalidate(); // Vẽ lại chart để hiển thị text mới
                    Toast.makeText(ChartActivity.this, "Lỗi Firebase: " + databaseError.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        };

        // Bắt đầu lắng nghe
        audioLevelsRef.addValueEventListener(audioLevelListener);
    }

    private void addEntry(float timeSeconds, float amplitude) {
        // Đảm bảo các thao tác cập nhật UI của chart diễn ra trên UI thread
        runOnUiThread(() -> {
            if (lineChart.getData() == null) {
                Log.e(TAG, "Chart data is null in addEntry!");
                lineChart.setData(new LineData()); // Khởi tạo nếu null
            }
            LineData data = lineChart.getData();

            if (data != null) {
                LineDataSet set = (LineDataSet) data.getDataSetByIndex(0);
                // Nếu dataset chưa tồn tại, tạo mới và thêm vào LineData
                if (set == null) {
                    set = createSet();
                    data.addDataSet(set);
                }

                // Thêm điểm dữ liệu mới
                data.addEntry(new Entry(timeSeconds, amplitude), 0);
                // Thông báo cho LineData biết rằng dữ liệu đã thay đổi
                data.notifyDataChanged();

                // Thông báo cho biểu đồ biết rằng dữ liệu đã thay đổi
                lineChart.notifyDataSetChanged();

                // *** Xóa điểm dữ liệu cũ nếu vượt quá giới hạn ***
                if (set.getEntryCount() > MAX_DATA_POINTS) {
                    // Lấy entry cũ nhất để xóa
                    Entry entryToRemove = set.getEntryForIndex(0);
                    data.removeEntry(entryToRemove, 0); // Xóa khỏi data
                    // Không cần gọi set.removeFirst() nữa vì đã xóa khỏi data
                    // Cần gọi lại notify để cập nhật thay đổi này
                    // lineChart.notifyDataSetChanged(); // Đã gọi ở trên
                }
                // ***********************************************

                // Giới hạn số lượng điểm hiển thị trên trục X
                lineChart.setVisibleXRangeMaximum(MAX_VISIBLE_ENTRIES);

                // Tự động cuộn biểu đồ đến điểm dữ liệu mới nhất
                lineChart.moveViewToX(timeSeconds);

                // Buộc vẽ lại biểu đồ sau khi di chuyển view
                lineChart.invalidate();

                Log.d(TAG, "Chart updated with new entry. Total entries: " + set.getEntryCount());
            } else {
                Log.e(TAG, "LineData is unexpectedly null after check!");
            }
        });
    }

    // Hàm tạo LineDataSet mới
    private LineDataSet createSet() {
        LineDataSet set = new LineDataSet(null, "Biên độ âm thanh (RMS)"); // Label cho dataset
        set.setAxisDependency(YAxis.AxisDependency.LEFT); // Sử dụng trục Y bên trái
        set.setColor(Color.BLUE); // Màu đường line
        set.setCircleColor(Color.BLUE); // Màu điểm tròn
        set.setLineWidth(2f); // Độ dày đường line
        set.setCircleRadius(1.5f); // Giảm bán kính điểm tròn cho đỡ rối
        set.setFillAlpha(65); // Độ trong suốt của vùng tô màu dưới line
        set.setFillColor(Color.BLUE); // Màu tô dưới line
        set.setDrawCircleHole(false); // Không vẽ lỗ ở giữa điểm tròn
        set.setDrawValues(false); // Không hiển thị giá trị số trên các điểm
        set.setMode(LineDataSet.Mode.LINEAR); // Đổi sang LINEAR để đơn giản hơn, CUBIC_BEZIER đôi khi gây lỗi vẽ
        // set.setCubicIntensity(0.2f); // Bỏ comment nếu muốn dùng CUBIC_BEZIER
        set.setDrawFilled(true); // Bật tô màu dưới đường line (tùy chọn)
        return set;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Gỡ bỏ listener khi activity bị hủy để tránh rò rỉ bộ nhớ và lỗi
        if (audioLevelsRef != null && audioLevelListener != null) {
            Log.d(TAG, "Removing Firebase listener.");
            audioLevelsRef.removeEventListener(audioLevelListener);
            audioLevelListener = null; // Đặt listener thành null
            audioLevelsRef = null; // Đặt reference thành null
        }
        Log.d(TAG, "ChartActivity destroyed.");
    }
}
