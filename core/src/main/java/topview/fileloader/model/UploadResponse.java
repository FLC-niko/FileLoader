package topview.fileloader.model;

/**
 * 上传接口响应数据模型
 * 映射服务器返回的 JSON 结构
 */
public class UploadResponse {
    // 响应状态码（如 200 表示成功）
    private int code;

    // 服务器返回的消息描述
    private String message;

    // 成功时的业务数据负载
    private UploadData data;

    // 操作是否成功的便捷标记
    private boolean success;

    /**
     * 无参构造函数（用于反序列化）
     */
    public UploadResponse() {}

    /**
     * 全参构造函数
     */
    public UploadResponse(int code, String message, boolean success) {
        this.code = code;
        this.message = message;
        this.success = success;
    }

    // --- Getter 和 Setter 方法 ---
    
    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public UploadData getData() { return data; }
    public void setData(UploadData data) { this.data = data; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    @Override
    public String toString() {
        return "UploadResponse{" +
                "code=" + code +
                ", message='" + message + '\'' +
                ", data=" + data +
                ", success=" + success +
                '}';
    }

    /**
     * 内部类：特定于上传成功的详细数据
     */
    public static class UploadData {
        // 文件在服务器端的唯一标识
        private String fileId;

        // 文件的下载或预览 URL
        private String url;
        private String uploadTime;
        private String batchId;

        public UploadData() {}

        public UploadData(String fileId, String url) {
            this.fileId = fileId;
            this.url = url;
        }

        public String getFileId() { return fileId; }
        public void setFileId(String fileId) { this.fileId = fileId; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getUploadTime() { return uploadTime; }
        public void setUploadTime(String uploadTime) { this.uploadTime = uploadTime; }

        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }

        @Override
        public String toString() {
            return "UploadData{" +
                    "fileId='" + fileId + '\'' +
                    ", url='" + url + '\'' +
                    ", uploadTime='" + uploadTime + '\'' +
                    ", batchId='" + batchId + '\'' +
                    '}';
        }
    }
}
