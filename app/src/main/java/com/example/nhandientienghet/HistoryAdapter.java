package com.example.nhandientienghet;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton; // Import ImageButton
import android.widget.ProgressBar; // Import ProgressBar
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder> {

    private List<HistoryItem> historyItemList;
    private Context context;
    private OnItemInteractionListener listener; // Interface để xử lý click

    // --- State Tracking ---
    private int playingPosition = -1; // Vị trí item đang phát (-1 là không có)
    private boolean isPlaying = false; // Trạng thái đang phát hay tạm dừng
    private int currentProgress = 0; // Tiến trình hiện tại (0-100)

    // Interface cho việc xử lý click và tương tác
    public interface OnItemInteractionListener {
        // void onItemClick(int position, HistoryItem item); // Bỏ nếu không cần click cả item
        void onPlayPauseClick(int position, HistoryItem item); // Xử lý khi nhấn nút Play/Pause
    }

    public HistoryAdapter(Context context, List<HistoryItem> historyItemList, OnItemInteractionListener listener) {
        this.context = context;
        this.historyItemList = historyItemList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.history_list_item, parent, false);
        return new HistoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
        HistoryItem currentItem = historyItemList.get(position);

        holder.tvTimestamp.setText(formatTimestamp(currentItem.getTimestamp()));

        // Chỉ hiển thị nút Play/Pause nếu có s3Key
        if (currentItem.getS3Key() != null && !currentItem.getS3Key().isEmpty()) {
            holder.btnPlayPause.setVisibility(View.VISIBLE);

            // Cập nhật trạng thái của item này dựa trên trạng thái chung
            if (position == playingPosition) {
                holder.progressBarAudio.setVisibility(View.VISIBLE);
                holder.progressBarAudio.setProgress(currentProgress);
                holder.btnPlayPause.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            } else {
                // Reset trạng thái cho các item không phát
                holder.progressBarAudio.setVisibility(View.GONE);
                holder.progressBarAudio.setProgress(0);
                holder.btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            }

            // Xử lý khi nhấn vào nút Play/Pause
            holder.btnPlayPause.setOnClickListener(v -> {
                if (listener != null) {
                    Log.d("HistoryAdapter", "Play/Pause button clicked for position: " + position);
                    listener.onPlayPauseClick(position, currentItem); // Gọi listener với position và item
                }
            });
        } else {
            // Ẩn các điều khiển nếu không có audio
            holder.btnPlayPause.setVisibility(View.GONE);
            holder.progressBarAudio.setVisibility(View.GONE);
            holder.btnPlayPause.setOnClickListener(null); // Bỏ listener
        }

        // Bỏ xử lý click vào toàn bộ item nếu không cần thiết
        // holder.itemView.setOnClickListener(v -> {
        //     if (listener != null) {
        //         listener.onItemClick(position, currentItem);
        //     }
        // });
    }

    @Override
    public int getItemCount() {
        return historyItemList.size();
    }

    // --- Public methods for Activity to update state ---

    /**
     * Cập nhật trạng thái đang phát/tạm dừng cho một item.
     * @param position Vị trí của item.
     * @param playing True nếu đang phát, false nếu tạm dừng hoặc dừng hẳn.
     */
    public void setPlayingState(int position, boolean playing) {
        int previousPlayingPosition = playingPosition;
        playingPosition = position; // Cập nhật vị trí đang phát mới
        isPlaying = playing;

        // Reset progress nếu dừng hẳn (position = -1)
        if (playingPosition == -1) {
            currentProgress = 0;
        }

        // Cập nhật lại item vừa thay đổi trạng thái
        if (position >= 0 && position < getItemCount()) {
            notifyItemChanged(position);
        }
        // Cập nhật lại item cũ nếu nó khác item mới (để reset trạng thái)
        if (previousPlayingPosition != -1 && previousPlayingPosition != position && previousPlayingPosition < getItemCount()) {
            notifyItemChanged(previousPlayingPosition);
        }
    }

    /**
     * Cập nhật tiến trình cho item đang phát.
     * @param progress Tiến trình hiện tại (0-100).
     */
    public void updateProgress(int progress) {
        if (playingPosition != -1 && playingPosition < getItemCount()) {
            currentProgress = progress;
            // Chỉ cập nhật item đang phát
            notifyItemChanged(playingPosition);
        }
    }

    /**
     * Lấy vị trí item đang phát hiện tại.
     */
    public int getPlayingPosition() {
        return playingPosition;
    }

    // ViewHolder class
    public static class HistoryViewHolder extends RecyclerView.ViewHolder {
        TextView tvTimestamp;
        ImageButton btnPlayPause; // Đổi thành ImageButton
        ProgressBar progressBarAudio; // Thêm ProgressBar

        public HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTimestamp = itemView.findViewById(R.id.tvHistoryTimestamp);
            btnPlayPause = itemView.findViewById(R.id.btnHistoryPlayPause); // Ánh xạ ImageButton
            progressBarAudio = itemView.findViewById(R.id.progressBarAudio); // Ánh xạ ProgressBar
        }
    }

    // Hàm helper để format timestamp ISO 8601 (Giữ nguyên)
    private String formatTimestamp(String isoTimestamp) {
        if (isoTimestamp == null) return "N/A";
        // ... (giữ nguyên phần code format timestamp)
        try {
            SimpleDateFormat isoFormatWithMsZ = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US);
            isoFormatWithMsZ.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat isoFormatWithMsOffset = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", Locale.US);
            SimpleDateFormat isoFormatZ = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            isoFormatZ.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat isoFormatOffset = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);

            Date date = null;
            try { date = isoFormatWithMsZ.parse(isoTimestamp); } catch (ParseException e1) {
                try { date = isoFormatWithMsOffset.parse(isoTimestamp); } catch (ParseException e2) {
                    try { date = isoFormatZ.parse(isoTimestamp); } catch (ParseException e3) {
                        try { date = isoFormatOffset.parse(isoTimestamp); } catch (ParseException e4) {
                            try {
                                SimpleDateFormat simpleIso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                                simpleIso.setTimeZone(TimeZone.getTimeZone("UTC"));
                                String simplifiedTimestamp = isoTimestamp;
                                if (isoTimestamp.contains(".")) { simplifiedTimestamp = isoTimestamp.substring(0, isoTimestamp.indexOf('.')); }
                                else if (isoTimestamp.contains("+") || isoTimestamp.endsWith("Z")) { simplifiedTimestamp = isoTimestamp.substring(0, 19); }
                                date = simpleIso.parse(simplifiedTimestamp);
                            } catch (Exception pe) {
                                Log.e("HistoryAdapter", "Error parsing timestamp after multiple attempts: " + isoTimestamp, pe);
                                return isoTimestamp;
                            }}}}}

            SimpleDateFormat outputFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
            outputFormat.setTimeZone(TimeZone.getDefault());
            return outputFormat.format(date);
        } catch (Exception e) {
            Log.e("HistoryAdapter", "General error formatting timestamp: " + isoTimestamp, e);
            return isoTimestamp;
        }
    }
}
