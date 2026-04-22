package topview.fileloader.service;

import topview.fileloader.config.AppConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
 * 批次ID服务类
 * 负责从服务器获取 batchId，替代本地时间戳生成
 */
public class BatchIdService {
    private static final Logger logger = Logger.getLogger(BatchIdService.class.getName());

    public static class BatchIdResult {
        private final int code;
        private final String msg;
        private final String batchId;

        public BatchIdResult(int code, String msg, String batchId) {
            this.code = code;
            this.msg = msg;
            this.batchId = batchId;
        }

        public int getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }

        public String getBatchId() {
            return batchId;
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
     * 从服务器获取批次ID（GET /batch/getBatchId）
     *
     * @return BatchIdResult，包含业务码、消息和 batchId
     */
    public static BatchIdResult fetchBatchId() {
        String urlStr = buildGetBatchIdUrl();
        logger.info("Fetching batchId: " + urlStr);
        HttpURLConnection conn = null;
        try {
            conn = openConnection(urlStr);
            conn.setRequestMethod("GET");
            int httpCode = conn.getResponseCode();
            String body = readResponseBody(conn, httpCode);
            logger.info("getBatchId response: " + body);

            if (httpCode != 200) {
                logger.warning("getBatchId HTTP " + httpCode);
                return new BatchIdResult(httpCode, "获取批次ID失败 (HTTP " + httpCode + ")", null);
            }

            int bizCode = parseIntField(body, "code", -1);
            String msg = parseStringField(body, "msg");
            if (msg == null || msg.isBlank()) {
                msg = (bizCode == 200) ? "获取成功" : "获取批次ID失败";
            }
            String batchId = parseStringField(body, "batchId");
            return new BatchIdResult(bizCode, msg, batchId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "getBatchId error", e);
            return new BatchIdResult(-1, "获取批次ID异常: " + e.getMessage(), null);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---- 内部辅助方法 ----

    private static String buildGetBatchIdUrl() {
        String base = buildBaseUrl();
        StringBuilder query = new StringBuilder();
        String userId = AppConfig.userId;
        if (userId != null && !userId.isBlank()) {
            query.append("userId=").append(URLEncoder.encode(userId, StandardCharsets.UTF_8));
        }
        String encryptedPassword = AppConfig.getEncryptedPassword();
        if (encryptedPassword != null && !encryptedPassword.isBlank()) {
            if (query.length() > 0) {
                query.append("&");
            }
            query.append("password=").append(URLEncoder.encode(encryptedPassword, StandardCharsets.UTF_8));
        }
        String url = base + "batch/getBatchId";
        if (query.length() > 0) {
            url += "?" + query.toString();
        }
        return url;
    }

    private static String buildBaseUrl() {
        String url = AppConfig.getServerUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
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

    private static String readResponseBody(HttpURLConnection conn, int httpCode) throws IOException {
        InputStream stream = (httpCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) {
            stream = new ByteArrayInputStream(new byte[0]);
        }
        byte[] bytes = stream.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private static int parseIntField(String json, String field, int defaultValue) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) {
            return defaultValue;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return defaultValue;
        }
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end <= start) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String parseStringField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) != '"') {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        if (end <= start) {
            return null;
        }
        return json.substring(start + 1, end).trim();
    }
}
