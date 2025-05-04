package com.example.nhandientienghet;

public class HistoryItem {
    private String id; // ID của document từ Firestore
    private String timestamp; // Thời gian dạng chuỗi ISO 8601
    private String clientIp;
    private String s3Key; // Có thể là null

    // Constructor
    public HistoryItem(String id, String timestamp, String clientIp, String s3Key) {
        this.id = id;
        this.timestamp = timestamp;
        this.clientIp = clientIp;
        this.s3Key = s3Key;
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getS3Key() {
        return s3Key;
    }

    // (Optional) Setter nếu cần
}
