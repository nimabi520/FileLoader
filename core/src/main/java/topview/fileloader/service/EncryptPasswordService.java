package topview.fileloader.service;

import topview.fileloader.config.AppConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 密码加密服务（POST /api/auth/encryptPassword）。
 * 将明文密码通过后端 AES 加密后返回，供后续 /batch 等接口调用。
 */
public class EncryptPasswordService {
    private static final Logger logger = Logger.getLogger(EncryptPasswordService.class.getName());

    private static SSLSocketFactory trustAllSslSocketFactory;
    private static HostnameVerifier trustAllHostnameVerifier;

    static {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        @Override
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

    public enum State {
        SUCCESS,
        NETWORK_ERROR,
        SERVER_ERROR,
        PARSE_ERROR
    }

    public static class EncryptPasswordResult {
        private final State state;
        private final int httpCode;
        private final String encryptedPassword;
        private final String userId;
        private final String message;

        public EncryptPasswordResult(State state, int httpCode, String encryptedPassword, String userId, String message) {
            this.state = state;
            this.httpCode = httpCode;
            this.encryptedPassword = encryptedPassword;
            this.userId = userId;
            this.message = message;
        }

        public State getState() {
            return state;
        }

        public int getHttpCode() {
            return httpCode;
        }

        public String getEncryptedPassword() {
            return encryptedPassword;
        }

        public String getUserId() {
            return userId;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * 调用后端接口加密密码。
     *
     * @param userId   用户ID
     * @param password 明文密码
     * @return 加密结果
     */
    public static EncryptPasswordResult encryptPassword(String userId, String password) {
        String urlStr = buildBaseUrl() + "api/auth/encryptPassword";
        HttpURLConnection conn = null;
        try {
            conn = openConnection(urlStr);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            String requestBody = "{\"userId\":\"" + escapeJson(userId) + "\",\"password\":\"" + escapeJson(password) + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int httpCode = conn.getResponseCode();
            String body = readResponseBody(conn, httpCode);

            if (httpCode != 200) {
                String msg = parseStringField(body, "msg");
                if (msg == null || msg.isBlank()) {
                    msg = "密码加密失败 (HTTP " + httpCode + ")";
                }
                return new EncryptPasswordResult(State.SERVER_ERROR, httpCode, null, null, msg);
            }

            int bizCode = parseIntField(body, "code", Integer.MIN_VALUE);
            String msg = parseStringField(body, "msg");
            if (msg == null) {
                msg = "";
            }

            if (bizCode != Integer.MIN_VALUE && bizCode != 0 && bizCode != 200) {
                String serverMsg = msg.isBlank() ? ("服务端返回异常业务码: " + bizCode) : msg;
                return new EncryptPasswordResult(State.SERVER_ERROR, httpCode, null, null, serverMsg);
            }

            String dataBlock = extractDataBlock(body);
            if (dataBlock == null) {
                return new EncryptPasswordResult(State.PARSE_ERROR, httpCode, null, null, "响应中缺少 data 字段");
            }

            String encryptedPassword = parseStringField(dataBlock, "encryptedPassword");
            String returnedUserId = parseStringField(dataBlock, "userId");

            if (encryptedPassword == null || encryptedPassword.isBlank()) {
                return new EncryptPasswordResult(State.PARSE_ERROR, httpCode, null, null, "响应中缺少 encryptedPassword 字段");
            }

            return new EncryptPasswordResult(State.SUCCESS, httpCode, encryptedPassword, returnedUserId, msg.isBlank() ? "加密成功" : msg);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Encrypt password request failed", e);
            return new EncryptPasswordResult(State.NETWORK_ERROR, -1, null, null, "网络异常: " + e.getMessage());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Encrypt password parse failed", e);
            return new EncryptPasswordResult(State.PARSE_ERROR, -1, null, null, "响应解析异常: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
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

    private static String extractDataBlock(String json) {
        int idx = json.indexOf("\"data\"");
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
        if (start >= json.length() || json.charAt(start) != '{') {
            return null;
        }
        int braceCount = 0;
        int end = -1;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '{') {
                braceCount++;
            } else if (json.charAt(i) == '}') {
                braceCount--;
                if (braceCount == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        if (end <= start) {
            return null;
        }
        return json.substring(start, end);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
