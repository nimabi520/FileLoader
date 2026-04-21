package topview.fileloader.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 应用程序配置类
 * 负责读取、保存和管理应用程序的各项配置参数，如服务器地址、超时时间等
 */
public class AppConfig {
    // 日志记录器
    private static final Logger logger = Logger.getLogger(AppConfig.class.getName());
    // 配置文件路径（迁移到用户目录，避免污染仓库）
    private static final Path APP_HOME_DIR = Paths.get(System.getProperty("user.home"), ".fileloader");
    private static final Path CONFIG_FILE = APP_HOME_DIR.resolve("fileLoader.properties");
    private static final Path LEGACY_CONFIG_FILE = Paths.get("fileLoader.properties");
    // 配置属性集合
    private static final Properties properties = new Properties();

    // 默认配置常量
    private static final String DEFAULT_SERVER_URL = "http://10.21.76.73:8081/";
    private static final int DEFAULT_CONNECT_TIMEOUT = 30;
    private static final int DEFAULT_READ_TIMEOUT = 60;
    private static final int DEFAULT_WRITE_TIMEOUT = 60;
    private static final String DEFAULT_USER_ID = "20000381";

    // 默认文件过滤配置：false 表示不过滤（上传所有文件），true 表示只上传 PDF
    private static final boolean DEFAULT_ONLY_UPLOAD_PDF = true;

    // 默认用户Id
    public static String userId = DEFAULT_USER_ID;
    // 加密后的密码
    public static String encryptedPassword = "";

    static {
        // 类加载时自动读取配置
        loadConfig();
    }

    /**
     * 从属性文件中加载配置信息
     */
    public static void loadConfig() {
        ensureConfigDirectory();
        migrateLegacyConfigIfNeeded();

        try (InputStream fis = Files.newInputStream(CONFIG_FILE)) {
            properties.load(fis);
            logger.info("Configuration loaded from " + CONFIG_FILE);
        } catch (IOException e) {
            logger.warning("Could not load config file, using defaults: " + e.getMessage());
            setDefaults();
        }

        userId = properties.getProperty("user.id", DEFAULT_USER_ID);
        encryptedPassword = properties.getProperty("user.encryptedPassword", "");
    }

    /**
     * 将当前配置信息保存到属性文件中
     */
    public static void saveConfig() {
        ensureConfigDirectory();
        properties.setProperty("user.id", userId);
        properties.setProperty("user.encryptedPassword", encryptedPassword);
        try (OutputStream fos = Files.newOutputStream(CONFIG_FILE)) {
            properties.store(fos, "FileLoader Configuration");
            logger.info("Configuration saved to " + CONFIG_FILE);
        } catch (IOException e) {
            logger.severe("Could not save config file: " + e.getMessage());
        }
    }

    /**
     * 设置配置的默认值
     */
    private static void setDefaults() {
        properties.setProperty("server.url", DEFAULT_SERVER_URL);
        properties.setProperty("timeout.connect", String.valueOf(DEFAULT_CONNECT_TIMEOUT));
        properties.setProperty("timeout.read", String.valueOf(DEFAULT_READ_TIMEOUT));
        properties.setProperty("timeout.write", String.valueOf(DEFAULT_WRITE_TIMEOUT));
        properties.setProperty("filter.onlyPdf", String.valueOf(DEFAULT_ONLY_UPLOAD_PDF));
        properties.setProperty("user.id", DEFAULT_USER_ID);
        properties.setProperty("user.encryptedPassword", "");
        userId = DEFAULT_USER_ID;
        encryptedPassword = "";
    }

    // --- Getter 方法，如果配置不存在则返回默认值 ---

    /**
     * 获取服务器URL
     */
    public static String getServerUrl() {
        return properties.getProperty("server.url", DEFAULT_SERVER_URL);
    }

    /**
     * 获取连接超时时间
     */
    public static int getConnectTimeout() {
        return Integer.parseInt(properties.getProperty("timeout.connect", String.valueOf(DEFAULT_CONNECT_TIMEOUT)));
    }

    /**
     * 获取读取超时时间
     */
    public static int getReadTimeout() {
        return Integer.parseInt(properties.getProperty("timeout.read", String.valueOf(DEFAULT_READ_TIMEOUT)));
    }

    /**
     * 获取写入超时时间
     */
    public static int getWriteTimeout() {
        return Integer.parseInt(properties.getProperty("timeout.write", String.valueOf(DEFAULT_WRITE_TIMEOUT)));
    }

    /**
     * 获取是否只上传 PDF 文件的配置
     */
    public static boolean isOnlyUploadPdf() {
        return Boolean.parseBoolean(properties.getProperty("filter.onlyPdf", String.valueOf(DEFAULT_ONLY_UPLOAD_PDF)));
    }

    /**
     * 设置并保存“仅上传 PDF”开关
     */
    public static void setOnlyUploadPdf(boolean value) {
        properties.setProperty("filter.onlyPdf", String.valueOf(value));
        saveConfig();
    }

    // --- Setter 方法，修改后自动保存 ---

    /**
     * 设置并保存服务器URL
     */
    public static void setServerUrl(String url) {
        properties.setProperty("server.url", url);
        saveConfig();
    }

    public static void setConnectTimeout(int timeoutSeconds) {
        properties.setProperty("timeout.connect", String.valueOf(timeoutSeconds));
        saveConfig();
    }

    public static void setReadTimeout(int timeoutSeconds) {
        properties.setProperty("timeout.read", String.valueOf(timeoutSeconds));
        saveConfig();
    }

    public static void setWriteTimeout(int timeoutSeconds) {
        properties.setProperty("timeout.write", String.valueOf(timeoutSeconds));
        saveConfig();
    }

    public static String getUserId() {
        return userId;
    }

    public static void setUserId(String value) {
        userId = (value == null || value.isBlank()) ? DEFAULT_USER_ID : value.trim();
        properties.setProperty("user.id", userId);
        saveConfig();
    }

    public static String getEncryptedPassword() {
        return encryptedPassword;
    }

    public static void setEncryptedPassword(String value) {
        encryptedPassword = value == null ? "" : value;
        properties.setProperty("user.encryptedPassword", encryptedPassword);
        saveConfig();
    }

    public static void clearEncryptedPassword() {
        encryptedPassword = "";
        properties.setProperty("user.encryptedPassword", "");
        saveConfig();
    }

    /**
     * 若已保存加密密码，在 URL 后追加 userId 和 password query 参数。
     */
    public static String appendAuthQueryParams(String url) {
        String pwd = encryptedPassword;
        if (pwd == null || pwd.isBlank()) {
            return url;
        }
        String uid = userId;
        if (uid == null || uid.isBlank()) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "userId=" + URLEncoder.encode(uid, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(pwd, StandardCharsets.UTF_8);
    }

    private static void ensureConfigDirectory() {
        try {
            Files.createDirectories(APP_HOME_DIR);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not create app home directory: " + APP_HOME_DIR, e);
        }
    }

    private static void migrateLegacyConfigIfNeeded() {
        if (!Files.exists(LEGACY_CONFIG_FILE) || Files.exists(CONFIG_FILE)) {
            return;
        }
        try {
            Files.copy(LEGACY_CONFIG_FILE, CONFIG_FILE, StandardCopyOption.COPY_ATTRIBUTES);
            logger.info("Migrated legacy config to " + CONFIG_FILE);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not migrate legacy config file", e);
        }
    }
}
