package topview.fileloader.service;

import topview.fileloader.config.AppConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * 登录状态查询服务（GET /api/auth/login）。
 */
public class AuthStatusService {
    private static final Logger logger = Logger.getLogger(AuthStatusService.class.getName());

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
        LOGGED_IN,
        UNAUTHORIZED,
        NETWORK_ERROR,
        SERVER_ERROR,
        PARSE_ERROR
    }

    public enum LogoutState {
        SUCCESS,
        UNAUTHORIZED,
        NETWORK_ERROR,
        SERVER_ERROR
    }

    public static class LoginUser {
        private final boolean loggedIn;
        private final String userId;
        private final String name;
        private final int role;
        private final String sessionId;
        private final String loginTime;

        public LoginUser(boolean loggedIn, String userId, String name, int role, String sessionId, String loginTime) {
            this.loggedIn = loggedIn;
            this.userId = userId;
            this.name = name;
            this.role = role;
            this.sessionId = sessionId;
            this.loginTime = loginTime;
        }

        public boolean isLoggedIn() {
            return loggedIn;
        }

        public String getUserId() {
            return userId;
        }

        public String getName() {
            return name;
        }

        public int getRole() {
            return role;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getLoginTime() {
            return loginTime;
        }
    }

    public static class LoginStatusResult {
        private final State state;
        private final int httpCode;
        private final int bizCode;
        private final String message;
        private final LoginUser user;

        public LoginStatusResult(State state, int httpCode, int bizCode, String message, LoginUser user) {
            this.state = state;
            this.httpCode = httpCode;
            this.bizCode = bizCode;
            this.message = message;
            this.user = user;
        }

        public State getState() {
            return state;
        }

        public int getHttpCode() {
            return httpCode;
        }

        public int getBizCode() {
            return bizCode;
        }

        public String getMessage() {
            return message;
        }

        public LoginUser getUser() {
            return user;
        }
    }

    public static class LogoutResult {
        private final LogoutState state;
        private final int httpCode;
        private final String message;
        private final String redirectLocation;

        public LogoutResult(LogoutState state, int httpCode, String message, String redirectLocation) {
            this.state = state;
            this.httpCode = httpCode;
            this.message = message;
            this.redirectLocation = redirectLocation;
        }

        public LogoutState getState() {
            return state;
        }

        public int getHttpCode() {
            return httpCode;
        }

        public String getMessage() {
            return message;
        }

        public String getRedirectLocation() {
            return redirectLocation;
        }
    }

    /**
     * 查询当前登录状态。
     */
    public static LoginStatusResult queryLoginStatus() {
        String urlStr = AppConfig.appendAuthQueryParams(buildBaseUrl() + "api/auth/login");
        HttpURLConnection conn = null;
        try {
            conn = openConnection(urlStr);
            conn.setRequestMethod("GET");
            int httpCode = conn.getResponseCode();

            if (httpCode == 401 || httpCode == 403) {
                String body = readResponseBody(conn, httpCode);
                String msg = parseStringField(body, "msg");
                if (msg == null || msg.isBlank()) {
                    msg = "未登录或认证已过期";
                }
                return new LoginStatusResult(State.UNAUTHORIZED, httpCode, -1, msg, null);
            }

            if (httpCode != 200) {
                String body = readResponseBody(conn, httpCode);
                String msg = parseStringField(body, "msg");
                if (msg == null || msg.isBlank()) {
                    msg = "登录状态查询失败 (HTTP " + httpCode + ")";
                }
                return new LoginStatusResult(State.SERVER_ERROR, httpCode, -1, msg, null);
            }

            String body = readResponseBody(conn, httpCode);
            int bizCode = parseIntField(body, "code", Integer.MIN_VALUE);
            String msg = parseStringField(body, "msg");
            if (msg == null) {
                msg = "";
            }

            if (bizCode == 401 || bizCode == 403) {
                String unauthorizedMsg = msg.isBlank() ? "当前未登录" : msg;
                return new LoginStatusResult(State.UNAUTHORIZED, httpCode, bizCode, unauthorizedMsg, null);
            }

            if (containsDataNull(body)) {
                String unauthorizedMsg = msg.isBlank() ? "当前未登录" : msg;
                return new LoginStatusResult(State.UNAUTHORIZED, httpCode, bizCode, unauthorizedMsg, null);
            }

            Boolean loggedIn = parseBooleanFieldNullable(body, "loggedIn");
            if (loggedIn == null) {
                return new LoginStatusResult(State.PARSE_ERROR, httpCode, bizCode, "无法解析登录状态字段 loggedIn", null);
            }

            if (!loggedIn) {
                String unauthorizedMsg = msg.isBlank() ? "当前未登录" : msg;
                return new LoginStatusResult(State.UNAUTHORIZED, httpCode, bizCode, unauthorizedMsg, null);
            }

            if (bizCode != Integer.MIN_VALUE && bizCode != 0 && bizCode != 200) {
                String serverMsg = msg.isBlank() ? ("服务端返回异常业务码: " + bizCode) : msg;
                return new LoginStatusResult(State.SERVER_ERROR, httpCode, bizCode, serverMsg, null);
            }

            LoginUser user = new LoginUser(
                    true,
                    defaultString(parseStringField(body, "userId")),
                    defaultString(parseStringField(body, "name")),
                    parseIntField(body, "role", 0),
                    defaultString(parseStringField(body, "sessionId")),
                    defaultString(parseStringField(body, "loginTime")));

            return new LoginStatusResult(State.LOGGED_IN, httpCode, bizCode, msg.isBlank() ? "已登录" : msg, user);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Login status request failed", e);
            return new LoginStatusResult(State.NETWORK_ERROR, -1, -1, "网络异常: " + e.getMessage(), null);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Login status parse failed", e);
            return new LoginStatusResult(State.PARSE_ERROR, -1, -1, "响应解析异常: " + e.getMessage(), null);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 发起退出登录。
     */
    public static LogoutResult logout() {
        String urlStr = AppConfig.appendAuthQueryParams(buildBaseUrl() + "api/auth/logout");
        HttpURLConnection conn = null;
        try {
            conn = openConnection(urlStr);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);

            int httpCode = conn.getResponseCode();
            String body = readResponseBody(conn, httpCode);
            int bizCode = parseIntField(body, "code", Integer.MIN_VALUE);
            String msg = parseStringField(body, "msg");
            String location = conn.getHeaderField("Location");

            if (bizCode == 401 || bizCode == 403) {
                return new LogoutResult(
                        LogoutState.UNAUTHORIZED,
                        httpCode,
                        (msg == null || msg.isBlank()) ? "当前未登录" : msg,
                        location);
            }

            if (httpCode == 401 || httpCode == 403) {
                return new LogoutResult(
                        LogoutState.UNAUTHORIZED,
                        httpCode,
                        (msg == null || msg.isBlank()) ? "当前未登录" : msg,
                        location);
            }

            if (httpCode == 200 || httpCode == 204 || isRedirectCode(httpCode)) {
                if (bizCode != Integer.MIN_VALUE && bizCode != 0 && bizCode != 200) {
                    String serverMessage = (msg == null || msg.isBlank())
                            ? ("退出登录失败，业务码: " + bizCode)
                            : msg;
                    return new LogoutResult(LogoutState.SERVER_ERROR, httpCode, serverMessage, location);
                }

                String successMessage;
                if (msg != null && !msg.isBlank()) {
                    successMessage = msg;
                } else if (location != null && !location.isBlank()) {
                    successMessage = "退出请求已发起，跳转: " + location;
                } else {
                    successMessage = "退出登录成功";
                }
                return new LogoutResult(LogoutState.SUCCESS, httpCode, successMessage, location);
            }

            String serverMessage = (msg == null || msg.isBlank()) ? ("退出登录失败 (HTTP " + httpCode + ")") : msg;
            return new LogoutResult(LogoutState.SERVER_ERROR, httpCode, serverMessage, location);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Logout request failed", e);
            return new LogoutResult(LogoutState.NETWORK_ERROR, -1, "网络异常: " + e.getMessage(), null);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Logout request parse failed", e);
            return new LogoutResult(LogoutState.SERVER_ERROR, -1, "退出登录异常: " + e.getMessage(), null);
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

    private static Boolean parseBooleanFieldNullable(String json, String field) {
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
        if (start + 4 <= json.length() && json.substring(start, start + 4).equalsIgnoreCase("true")) {
            return true;
        }
        if (start + 5 <= json.length() && json.substring(start, start + 5).equalsIgnoreCase("false")) {
            return false;
        }
        return null;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static boolean containsDataNull(String json) {
        return json.contains("\"data\":null") || json.contains("\"data\" : null");
    }

    private static boolean isRedirectCode(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == HttpURLConnection.HTTP_NOT_MODIFIED
                || code == 307
                || code == 308;
    }
}
