package topview.fileloader.service;

import topview.fileloader.config.AppConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.*;

public class DownloadService {
    private static final Logger logger = Logger.getLogger(DownloadService.class.getName());

    private static SSLSocketFactory trustAllSslSocketFactory;
    private static HostnameVerifier trustAllHostnameVerifier;

    static {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
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

    public static String downloadBatch(String batchId, File saveDir, boolean withWrong) {
        String safeBatchId = batchId == null ? "" : batchId.trim();
        if (safeBatchId.isEmpty()) {
            logger.warning("Download failed: batchId is empty");
            return "下载失败: 批次ID为空";
        }

        String encodedBatchId = URLEncoder.encode(safeBatchId, StandardCharsets.UTF_8);
        String endpoint = "batch/" + encodedBatchId + (withWrong ? "/batchDownloadWithWrong" : "/batchDownload");
        String serverUrl = AppConfig.getServerUrl();
        if (!serverUrl.endsWith("/")) {
            serverUrl += "/";
        }
        
        String urlStr = AppConfig.appendAuthQueryParams(serverUrl + endpoint);
        logger.info("Download URL: " + urlStr);
        
        try {
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) connection;
                httpsConn.setSSLSocketFactory(trustAllSslSocketFactory);
                httpsConn.setHostnameVerifier(trustAllHostnameVerifier);
            }
            
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(AppConfig.getConnectTimeout() * 1000);
            connection.setReadTimeout(AppConfig.getReadTimeout() * 1000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            connection.setRequestProperty("Accept", "*/*");
            
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                String disposition = connection.getHeaderField("Content-Disposition");
                String fileName = "batch_" + batchId + (withWrong ? "_wrong" : "") + ".zip";
                
                if (disposition != null) {
                    // 处理 RFC 5987 编码格式：filename*=UTF-8''encoded-filename
                    int rfc5987Idx = disposition.indexOf("filename*=");
                    if (rfc5987Idx >= 0) {
                        String encodedPart = disposition.substring(rfc5987Idx + 10).trim();
                        // 提取 UTF-8'' 之后的实际文件名
                        int quoteIdx = encodedPart.indexOf("''");
                        if (quoteIdx >= 0) {
                            fileName = encodedPart.substring(quoteIdx + 2);
                            // 处理 URL 编码的文件名
                            try {
                                fileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
                            } catch (Exception e) {
                                // 如果解码失败，使用原始值
                            }
                        }
                    } else {
                        // 处理标准格式：filename="filename.zip"
                        int index = disposition.indexOf("filename=");
                        if (index > 0) {
                            String part = disposition.substring(index + 9).trim();
                            // 去掉引号和分号后的内容
                            int semicolonIdx = part.indexOf(';');
                            if (semicolonIdx >= 0) {
                                part = part.substring(0, semicolonIdx).trim();
                            }
                            fileName = part.replace("\"", "");
                        }
                    }
                }
                
                File saveFile = new File(saveDir, fileName);
                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(saveFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                logger.info("Downloaded successfully: " + saveFile.getAbsolutePath());
                return "下载成功: " + saveFile.getAbsolutePath();
            } else {
                String errorMessage = null;
                try (InputStream errorStream = connection.getErrorStream()) {
                    if (errorStream != null) {
                        byte[] errorBytes = errorStream.readAllBytes();
                        String errorText = new String(errorBytes, StandardCharsets.UTF_8).trim();
                        if (!errorText.isEmpty()) {
                            errorMessage = errorText;
                            logger.warning("Download error body: " + errorText);
                        }
                    }
                }
                
                // 构建更友好的错误消息
                String result;
                if (responseCode == 404) {
                    result = "没有可下载的文件（异常文件不存在）";
                    if (errorMessage != null) {
                        result = errorMessage;
                    }
                } else if (errorMessage != null) {
                    result = errorMessage;
                } else {
                    result = "下载失败，服务器响应码: " + responseCode;
                }
                
                logger.warning("Download failed with response code: " + responseCode + ", message: " + result);
                return result;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Download error", e);
            return "下载异常: " + e.getMessage();
        }
    }
}
