public class DownloadResponse {
     private int code;

    // 服务器返回的消息描述
    private String message;

    // 成功时的业务数据负载
    private DownloadData data;

    // 操作是否成功的便捷标记
    private boolean success;

    /**
     * 无参构造函数（用于反序列化）
     */
    public DownloadResponse() {}

    /**
     * 全参构造函数
     */
    public DownloadResponse(int code, String message, boolean success) {
        this.code = code;
        this.message = message;
        this.success = success;
    }

    /**
     * 内部类：特定于下载成功的详细数据
     */
    public static class DownloadData {
        // 文件在服务器端的唯一标识
        private String fileId;

        // 文件的下载或预览 URL
        private String url;

        public DownloadData() {}

        public DownloadData(String fileId, String url) {
            this.fileId = fileId;
            this.url = url;
        }

        public String getFileId() { return fileId; }
        public void setFileId(String fileId) { this.fileId = fileId; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        @Override
        public String toString() {
            return "DownloadData{" +
                    "fileId='" + fileId + '\'' +
                    ", url='" + url + '\'' +
                    '}';
        }
    }
}