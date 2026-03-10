import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.*;

/**
 * 批次状态与结果服务
 * 提供查询批次状态（batchStatus）和下载批次汇总报告（batchResult）的功能
 */
public class BatchStatusService {
    private static final Logger logger = Logger.getLogger(BatchStatusService.class.getName());

    public static class BatchStatusResult {
        private final int code;
        private final String msg;
        private final boolean downloadable;
        private final int unprocessedFiles;
        private final int processingFiles;
        private final int totalFiles;

        public BatchStatusResult(int code, String msg, boolean downloadable, int unprocessedFiles, int processingFiles, int totalFiles) {
            this.code = code;
            this.msg = msg;
            this.downloadable = downloadable;
            this.unprocessedFiles = unprocessedFiles;
            this.processingFiles = processingFiles;
            this.totalFiles = totalFiles;

        }

        public int getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }

        public boolean isDownloadable() {
            return downloadable;
        }

        public int getUnprocessedFiles() {
            return unprocessedFiles;
        }

        public int getProcessingFiles() {
            return processingFiles;
        }

        public int getTotalFiles() {
            return totalFiles;
        }
    }

    private static SSLSocketFactory trustAllSslSocketFactory;
    private static HostnameVerifier trustAllHostnameVerifier;

    static {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new SecureRandom());
            trustAllSslSocketFactory = sc.getSocketFactory();
            trustAllHostnameVerifier = (hostname, session) -> true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize SSL bypass", e);
        }
    }

    /**
     * 查询指定批次的状态（GET /batch/{batchId}/batchStatus）
     *
     * @param batchId 批次ID
     * @return 结构化状态结果，包含原始业务码、状态文案及是否可下载
     */
    public static BatchStatusResult fetchBatchStatus(String batchId) {
        String encodedId = URLEncoder.encode(batchId.trim(), StandardCharsets.UTF_8);
        String urlStr = buildBaseUrl() + "batch/" + encodedId + "/batchStatus";
        logger.info("Querying batchStatus: " + urlStr);
        try {
            HttpURLConnection conn = openConnection(urlStr);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 200) {
                byte[] bytes = conn.getInputStream().readAllBytes();
                String body = new String(bytes, StandardCharsets.UTF_8).trim();
                logger.info("batchStatus response: " + body);
                int bizCode = parseIntField(body, "code", -1);
                String msg = parseStringField(body, "msg");
                if (msg == null || msg.isBlank()) {
                    msg = (bizCode == 200) ? "查询成功" : "状态未知";
                }
                int unprocessedFiles = parseIntField(body, "unprocessedFiles", -1);
                int processingFiles = parseIntField(body, "processingFiles", -1);
                int totalFiles = parseIntField(body, "totalFiles", -1);
                boolean successFlag = parseBooleanField(body, "success");
                boolean downloadable = resolveDownloadable(bizCode, successFlag, msg, unprocessedFiles, processingFiles,
                        totalFiles);
                return new BatchStatusResult(bizCode, msg, downloadable, unprocessedFiles, processingFiles, totalFiles);
            } else {
                logger.warning("batchStatus HTTP " + code + " for batchId=" + batchId);
                return new BatchStatusResult(code, "查询失败 (HTTP " + code + ")", false, -1, -1, -1);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "batchStatus error", e);
            return new BatchStatusResult(-1, "查询异常: " + e.getMessage(), false, -1, -1, -1);
        }
    }

    /**
     * 下载批次汇总 Excel 报告
     *
     * @param batchId 批次ID
     * @param saveDir 保存目录
     * @return 操作结果描述
     */
    public static String downloadBatchResult(String batchId, File saveDir) {
        String encodedId = URLEncoder.encode(batchId.trim(), StandardCharsets.UTF_8);
        String urlStr = buildBaseUrl() + "batch/" + encodedId + "/batchResult";
        logger.info("Downloading batchResult: " + urlStr);
        try {
            HttpURLConnection conn = openConnection(urlStr);
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                // 尝试从 Content-Disposition 中获取文件名
                String disposition = conn.getHeaderField("Content-Disposition");
                String fileName = "batch_" + batchId + "_result.xlsx";
                if (disposition != null) {
                    int idx = disposition.indexOf("filename=");
                    if (idx >= 0) {
                        fileName = disposition.substring(idx + 9).replaceAll("\"", "").trim();
                    }
                }
                File saveFile = new File(saveDir, fileName);
                try (InputStream in = conn.getInputStream();
                        FileOutputStream out = new FileOutputStream(saveFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                logger.info("batchResult downloaded: " + saveFile.getAbsolutePath());
                return "下载成功: " + saveFile.getAbsolutePath();
            } else {
                logger.warning("batchResult HTTP " + responseCode);
                return "下载失败，服务器响应码: " + responseCode;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "batchResult download error", e);
            return "下载异常: " + e.getMessage();
        }
    }

    // ---- 内部辅助方法 ----

    private static String buildBaseUrl() {
        String url = AppConfig.getServerUrl();
        if (!url.endsWith("/"))
            url += "/";
        return url;
    }

    private static HttpURLConnection openConnection(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setSSLSocketFactory(trustAllSslSocketFactory);
            https.setHostnameVerifier(trustAllHostnameVerifier);
        }
        conn.setConnectTimeout(AppConfig.getConnectTimeout() * 1000);
        conn.setReadTimeout(AppConfig.getReadTimeout() * 1000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "FileLoader/1.0");
        return conn;
    }

    private static int parseIntField(String json, String field, int defaultValue) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0)
            return defaultValue;
        int colon = json.indexOf(':', idx);
        if (colon < 0)
            return defaultValue;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t'))
            start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-'))
            end++;
        if (end <= start)
            return defaultValue;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String parseStringField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0)
            return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0)
            return null;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t'))
            start++;
        if (start >= json.length() || json.charAt(start) != '"')
            return null;
        int end = json.indexOf('"', start + 1);
        if (end <= start)
            return null;
        return json.substring(start + 1, end).trim();
    }

    private static boolean parseBooleanField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0)
            return false;
        int colon = json.indexOf(':', idx);
        if (colon < 0)
            return false;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t'))
            start++;
        if (start + 4 <= json.length() && json.substring(start, start + 4).equalsIgnoreCase("true"))
            return true;
        if (start + 5 <= json.length() && json.substring(start, start + 5).equalsIgnoreCase("false"))
            return false;
        return false;
    }

    private static boolean resolveDownloadable(int bizCode, boolean successFlag, String msg,
            int unprocessedFiles, int processingFiles, int totalFiles) {
        boolean apiSuccess = (bizCode == 200) || successFlag;
        // 如果响应里带了进度统计字段，优先按统计结果判断是否可下载。
        // 条件：未处理文件 = 0，正在处理文件 = 0，总文件数 > 0
        if (unprocessedFiles >= 0 && processingFiles >= 0 && totalFiles >= 0) {
            return apiSuccess && unprocessedFiles == 0 && processingFiles == 0 && totalFiles > 0;
        }
        // 退回到 msg 文字匹配
        if (msg == null)
            return false;
        String lower = msg.toLowerCase();
        boolean msgAllows = msg.contains("完成") || msg.contains("可下载")
                || lower.contains("finished") || lower.contains("done")
                || lower.contains("ready") || lower.contains("download");
        return apiSuccess && msgAllows;
    }
}
