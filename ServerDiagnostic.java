import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * 服务器诊断工具类
 * 提供一系列测试方法来检查服务器的连通性、API响应和网络代理配置
 */
public class ServerDiagnostic {
    // 日志记录器
    private static final Logger logger = Logger.getLogger(ServerDiagnostic.class.getName());

    /**
     * 执行全面的服务器诊断
     * @param serverUrl 服务器基础 URL
     */
    public static void diagnoseServer(String serverUrl) {
        System.out.println("=== 服务器诊断开始 ===");
        System.out.println("服务器地址: " + serverUrl);

        // 1. 测试基础 TCP/HTTP 连接
        testBasicConnection(serverUrl);

        // 2. 测试根路径（检查 Web 服务器是否存活）
        testRootPath(serverUrl);

        // 3. 测试上传接口（模拟 POST 请求检查接口约束）
        testUploadPath(serverUrl);

        // 4. 检查系统代理设置（排除网络环境干扰）
        testProxySettings();

        System.out.println("=== 服务器诊断完成 ===");
    }

    /**
     * 测试最基础的 GET 连接
     */
    private static void testBasicConnection(String serverUrl) {
        System.out.println("\n1. 测试基本连接...");
        try {
            URL url = new URL(serverUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            System.out.println("响应码: " + responseCode);

            if (responseCode == 200) {
                System.out.println("√ 基本连接成功");
            } else {
                System.out.println("× 基本连接异常");
            }

        } catch (Exception e) {
            System.out.println("× 连接失败: " + e.getMessage());
        }
    }

    /**
     * 测试根路径并输出响应内容摘要
     */
    private static void testRootPath(String serverUrl) {
        System.out.println("\n2. 测试根路径...");
        try {
            String rootUrl = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
            URL url = new URL(rootUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            System.out.println("根路径响应码: " + responseCode);

            // 读取响应内容预览
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < 5) {
                    response.append(line).append("\n");
                    lineCount++;
                }
                System.out.println("响应内容预览: \n" + response.toString().trim());
            }

        } catch (Exception e) {
            System.out.println("× 根路径测试出错: " + e.getMessage());
        }
    }

    /**
     * 模拟 Multipart 上传请求发送测试数据
     */
    private static void testUploadPath(String serverUrl) {
        System.out.println("\n3. 测试上传路径...");
        try {
            String uploadUrl = serverUrl.endsWith("/") ? serverUrl + "upload" : serverUrl + "/upload";
            URL url = new URL(uploadUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            // 设置模拟的 Multipart Content-Type
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=test_boundary");

            // 发送模拟的测试 payload
            try (OutputStream os = connection.getOutputStream()) {
                String testData = "--test_boundary\r\n" +
                        "Content-Disposition: form-data; name=\"test\"\r\n" +
                        "\r\n" +
                        "diagnostic_test\r\n" +
                        "--test_boundary--\r\n";
                os.write(testData.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            System.out.println("上传路径响应码: " + responseCode);

            // 读取响应内容
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
                System.out.println("接口返回内容: " + response.toString().trim());
            }

        } catch (Exception e) {
            System.out.println("× 上传接口测试出错: " + e.getMessage());
        }
    }

    /**
     * 检查当前 JVM 识别到的网络代理设置
     */
    private static void testProxySettings() {
        System.out.println("\n4. 检查代理设置...");

        String httpProxy = System.getProperty("http.proxyHost");
        String httpsProxy = System.getProperty("https.proxyHost");
        String httpProxyPort = System.getProperty("http.proxyPort");
        String httpsProxyPort = System.getProperty("https.proxyPort");

        if (httpProxy != null) {
            System.out.println("系统 HTTP 代理: " + httpProxy + ":" + httpProxyPort);
        } else {
            System.out.println("系统 HTTP 代理: 未设置");
        }

        if (httpsProxy != null) {
            System.out.println("系统 HTTPS 代理: " + httpsProxy + ":" + httpsProxyPort);
        } else {
            System.out.println("系统 HTTPS 代理: 未设置");
        }

        // 检查环境变量
        String envHttpProxy = System.getenv("HTTP_PROXY");
        String envHttpsProxy = System.getenv("HTTPS_PROXY");

        if (envHttpProxy != null) {
            System.out.println("环境变量 HTTP_PROXY: " + envHttpProxy);
        }
        if (envHttpsProxy != null) {
            System.out.println("环境变量 HTTPS_PROXY: " + envHttpsProxy);
        }
    }

    /**
     * 独立的诊断入口，方便手动运行
     */
    public static void main(String[] args) {
        String serverUrl = "http://10.21.32.130:8848/";
        if (args.length > 0) {
            serverUrl = args[0];
        }
        diagnoseServer(serverUrl);
    }
}
