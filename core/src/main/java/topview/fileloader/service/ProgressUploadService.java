package topview.fileloader.service;

import topview.fileloader.config.AppConfig;
import topview.fileloader.model.UploadResponse;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.*;

/**
 * 文件上传服务类
 * 负责将文件上传到远程服务器，支持进度回调和失败重试机制
 */
public class ProgressUploadService {
    // 日志记录器
    private static final Logger logger = Logger.getLogger(ProgressUploadService.class.getName());
    // 最大重试次数
    private static final int MAX_RETRIES = 3;
    // 重试延迟时间（毫秒）
    private static final int RETRY_DELAY_MS = 1000;

    // 忽略 SSL 证书验证的上下文
    private static SSLSocketFactory trustAllSslSocketFactory;
    private static HostnameVerifier trustAllHostnameVerifier;

    static {
        try {
            // 创建信任所有证书的 TrustManager
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

            // 安装信任所有证书的 SSLContext
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new SecureRandom());
            trustAllSslSocketFactory = sc.getSocketFactory();

            // 创建信任所有主机的 HostnameVerifier
            trustAllHostnameVerifier = (hostname, session) -> true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize SSL bypass", e);
        }
    }

    /**
     * 上传文件到服务器
     * 
     * @param file             要上传的文件对象
     * @param batchId          批次ID，如果为null则自动生成
     * @param progressCallback 进度回调函数，接收0-100的进度值
     * @return UploadResponse 上传响应对象，包含响应码和消息
     */
    public static UploadResponse uploadFile(File file, String batchId, Consumer<Integer> progressCallback) {
        if (batchId == null || batchId.isEmpty()) {
            batchId = String.valueOf(System.currentTimeMillis());
        }
        String userId = AppConfig.userId;
        // 最多尝试 MAX_RETRIES 次上传
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            HttpURLConnection connection = null;
            try {
                logger.info("开始上传文件: " + file.getName() + " (尝试 " + attempt + "/" + MAX_RETRIES + ")");
                logger.info("文件大小: " + file.length() + " 字节");

                // 获取服务器URL，确保末尾有斜杠
                String serverUrl = AppConfig.getServerUrl();
                if (!serverUrl.endsWith("/")) {
                    serverUrl += "/";
                }

                // 构建批量上传地址: POST batch/upload
                String urlStr = serverUrl + "batch/upload";
                StringBuilder queryParams = new StringBuilder();
                if (userId != null && !userId.isEmpty()) {
                    queryParams.append("userId=").append(java.net.URLEncoder.encode(userId, StandardCharsets.UTF_8));
                }
                if (batchId != null && !batchId.isEmpty()) {
                    if (queryParams.length() > 0)
                        queryParams.append("&");
                    queryParams.append("batchId=").append(java.net.URLEncoder.encode(batchId, StandardCharsets.UTF_8));
                }
                if (queryParams.length() > 0) {
                    urlStr += "?" + queryParams.toString();
                }
                URL url = new URL(urlStr);
                logger.info("上传地址: " + url.toString());

                // 创建HTTP连接
                connection = (HttpURLConnection) url.openConnection();

                // 如果是 HTTPS 请求，配置忽略证书验证
                if (connection instanceof HttpsURLConnection) {
                    HttpsURLConnection httpsConn = (HttpsURLConnection) connection;
                    httpsConn.setSSLSocketFactory(trustAllSslSocketFactory);
                    httpsConn.setHostnameVerifier(trustAllHostnameVerifier);
                }

                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setUseCaches(false);
                connection.setConnectTimeout(AppConfig.getConnectTimeout() * 1000);
                connection.setReadTimeout(AppConfig.getReadTimeout() * 1000);

                // 设置通用请求头，模拟浏览器行为防止被防火墙/WAF拦截
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                connection.setRequestProperty("Connection", "keep-alive");

                // 设置Multipart form-data格式的请求头
                String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
                String contentType = "multipart/form-data; boundary=" + boundary;
                connection.setRequestProperty("Content-Type", contentType);

                // 输出HTTP请求头信息用于调试
                System.out.println("\n=== HTTP 请求头 ===");
                System.out.println("POST " + url.getPath() + " HTTP/1.1");
                System.out.println("Host: " + url.getHost());
                System.out.println("Content-Type: " + contentType);
                System.out.println();

                // 计算总上传大小（包括文件头信息和额外的表单字段）
                long fileSize = file.length();
                long headerSize = calculateHeaderSize(file.getName(), boundary);
                long totalSize = fileSize + headerSize;

                logger.info("总上传大小: " + totalSize + " 字节");

                // 发送请求体
                try (OutputStream outputStream = connection.getOutputStream();
                        PrintWriter writer = new PrintWriter(
                                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true)) {

                    // 构建文件的multipart头部
                    String fileHeader = "--" + boundary + "\r\n" +
                            "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "\r\n";

                    System.out.println("=== Multipart 文件头部 ===");
                    System.out.print(fileHeader.replace("\r\n", "\n"));

                    writer.append(fileHeader);
                    writer.flush();

                    // 读取并上传文件内容，同时计算进度
                    long uploadedBytes = fileHeader.getBytes(StandardCharsets.UTF_8).length;
                    try (FileInputStream fileInputStream = new FileInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        int previewBytes = 0;
                        System.out.println("=== 文件内容前256字节（十六进制预览） ===");
                        while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            uploadedBytes += bytesRead;
                            // 输出文件前256字节的十六进制预览
                            if (previewBytes < 256) {
                                int toPrint = Math.min(256 - previewBytes, bytesRead);
                                for (int i = 0; i < toPrint; i++) {
                                    System.out.printf("%02X ", buffer[i]);
                                    if ((previewBytes + i + 1) % 16 == 0)
                                        System.out.print("\n");
                                }
                                previewBytes += toPrint;
                                if (previewBytes >= 256)
                                    System.out.println("\n...\n");
                            }
                            // 计算上传进度百分比并回调
                            int progress = (int) ((uploadedBytes * 100) / totalSize);
                            progressCallback.accept(progress);
                        }
                    }
                    outputStream.flush();

                    // 构建并发送额外的表单字段：fileName
                    String extraFields = "\r\n--" + boundary + "\r\n" +
                            "Content-Disposition: form-data; name=\"fileName\"\r\n" +
                            "\r\n" +
                            file.getName() + "\r\n" +
                            "--" + boundary + "--\r\n";

                    System.out.println("=== Multipart 参数部分 ===");
                    System.out.print(extraFields.replace("\r\n", "\n"));

                    writer.append(extraFields);
                    writer.flush();

                    progressCallback.accept(100);
                }

                // 获取服务器响应码
                int responseCode = connection.getResponseCode();
                logger.info("服务器响应码: " + responseCode);

                // 创建响应对象
                UploadResponse response = new UploadResponse();
                response.setCode(responseCode);

                // 读取服务器返回的响应体（getErrorStream() 在某些错误响应下可能为 null，需防御处理）
                InputStream bodyStream = (responseCode >= 400)
                        ? connection.getErrorStream()
                        : connection.getInputStream();
                if (bodyStream == null) {
                    bodyStream = new ByteArrayInputStream(new byte[0]);
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {

                    StringBuilder responseBody = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseBody.append(line);
                    }
                    String responseText = responseBody.toString();
                    logger.info("服务器响应: " + responseText);
                    response.setMessage(responseText.isEmpty() ? "HTTP " + responseCode : responseText);
                }

                // 判断上传是否成功
                if (responseCode == 200) {
                    response.setMessage("上传成功");
                    logger.info("上传成功: " + file.getName());
                    return response;
                } else {
                    logger.warning("上传失败: " + responseCode + " - " + response.getMessage());
                    // 如果未达到最大重试次数，进行重试
                    if (attempt < MAX_RETRIES) {
                        logger.info("等待重试，延迟 " + RETRY_DELAY_MS + "ms");
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    return response;
                }
            } catch (IOException e) {
                // 处理IO异常
                logger.log(Level.SEVERE, "上传IO异常 (尝试 " + attempt + "): " + file.getName(), e);
                if (attempt < MAX_RETRIES) {
                    try {
                        logger.info("等待重试，延迟 " + RETRY_DELAY_MS + "ms");
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.info("重试中断: " + file.getName());
                        break;
                    }
                    continue;
                }
                // 最后一次尝试失败，返回错误响应
                UploadResponse errorResponse = new UploadResponse();
                errorResponse.setCode(500);
                errorResponse.setMessage("网络错误: " + e.getMessage());
                return errorResponse;
            } catch (Exception e) {
                // 处理其他异常
                logger.log(Level.SEVERE, "上传异常 (尝试 " + attempt + "): " + file.getName(), e);
                if (attempt < MAX_RETRIES) {
                    try {
                        logger.info("等待重试，延迟 " + RETRY_DELAY_MS + "ms");
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.info("重试中断: " + file.getName());
                        break;
                    }
                    continue;
                }
                // 最后一次尝试失败，返回错误响应
                UploadResponse errorResponse = new UploadResponse();
                errorResponse.setCode(500);
                errorResponse.setMessage("未知错误: " + e.getMessage());
                return errorResponse;
            } finally {
                // 关闭HTTP连接
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        // 达到最大重试次数仍然失败
        UploadResponse errorResponse = new UploadResponse();
        errorResponse.setCode(500);
        errorResponse.setMessage("上传失败: 达到最大重试次数");
        return errorResponse;
    }

    /**
     * 计算Multipart form-data格式的文件头部和参数部分的总字节数
     * 
     * @param fileName 文件名
     * @param boundary multipart边界字符串
     * @return 头部和参数部分的总字节数
     */
    private static long calculateHeaderSize(String fileName, String boundary) {
        // 构建文件头部
        String fileHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n";

        // 构建额外的表单字段：fileName，包括结束边界
        String extraFields = "\r\n--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"fileName\"\r\n" +
                "\r\n" +
                fileName + "\r\n" +
                "--" + boundary + "--\r\n";

        // 返回两部分的总字节数
        return fileHeader.getBytes(StandardCharsets.UTF_8).length +
                extraFields.getBytes(StandardCharsets.UTF_8).length;
    }
}
