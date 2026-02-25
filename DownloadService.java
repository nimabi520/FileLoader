import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
        String endpoint = "batch/" + batchId + (withWrong ? "/batchDownloadWithWrong" : "/batchDownload");
        String serverUrl = AppConfig.getServerUrl();
        if (!serverUrl.endsWith("/")) {
            serverUrl += "/";
        }
        
        String urlStr = serverUrl + endpoint;
        
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
            
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                String disposition = connection.getHeaderField("Content-Disposition");
                String fileName = "batch_" + batchId + (withWrong ? "_wrong" : "") + ".zip";
                
                if (disposition != null) {
                    int index = disposition.indexOf("filename=");
                    if (index > 0) {
                        fileName = disposition.substring(index + 9).replace("\"", "");
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
                logger.warning("Download failed with response code: " + responseCode);
                return "下载失败，服务器响应码: " + responseCode;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Download error", e);
            return "下载异常: " + e.getMessage();
        }
    }
}
