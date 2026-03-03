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
     * 查询指定批次的状态
     *
     * @param batchId 批次ID
     * @return 状态描述字符串：如"处理成功"、"处理失败"、"上传中"，出错时返回"查询失败: ..."
     */
    public static String fetchBatchStatus(String batchId) {
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
                // 解析 JSON: {"code":0,"msg":"...","data":...}
                // 简单提取 msg 字段内容作为状态描述
                return parseMsg(body);
            } else {
                logger.warning("batchStatus HTTP " + code + " for batchId=" + batchId);
                return "查询失败 (HTTP " + code + ")";
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "batchStatus error", e);
            return "查询异常: " + e.getMessage();
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

    /**
     * 从简单 JSON 字符串中提取 msg 字段值
     * 例: {"code":0,"msg":"处理成功","data":null} -> "处理成功"
     */
    private static String parseMsg(String json) {
        // 尝试提取 "msg":"..." 的内容
        int idx = json.indexOf("\"msg\"");
        if (idx < 0)
            return json;
        int colon = json.indexOf(':', idx);
        if (colon < 0)
            return json;
        // 跳过空格
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t'))
            start++;
        if (start >= json.length())
            return json;
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end > start)
                return json.substring(start + 1, end);
        } else {
            // null or number
            int end = json.indexOf(',', start);
            if (end < 0)
                end = json.indexOf('}', start);
            if (end > start)
                return json.substring(start, end).trim();
        }
        return json;
    }
}
