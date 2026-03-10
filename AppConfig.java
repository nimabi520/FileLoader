import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 应用程序配置类
 * 负责读取、保存和管理应用程序的各项配置参数，如服务器地址、超时时间等
 */
public class AppConfig {
    // 日志记录器
    private static final Logger logger = Logger.getLogger(AppConfig.class.getName());
    // 配置文件路径
    private static final String CONFIG_FILE = "fileLoader.properties";
    // 配置属性集合
    static final Properties properties = new Properties();

    // 默认配置常量
    private static final String DEFAULT_SERVER_URL = "http://10.21.76.73:8081/";
    private static final int DEFAULT_CONNECT_TIMEOUT = 30;
    private static final int DEFAULT_READ_TIMEOUT = 60;
    private static final int DEFAULT_WRITE_TIMEOUT = 60;

    // 默认文件过滤配置：false 表示不过滤（上传所有文件），true 表示只上传 PDF
    private static final boolean DEFAULT_ONLY_UPLOAD_PDF = true;

    // 默认用户Id
    public static String userId = "20000381";

    static {
        // 类加载时自动读取配置
        loadConfig();
    }

    /**
     * 从属性文件中加载配置信息
     */
    public static void loadConfig() {
        try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
            properties.load(fis);
            logger.info("Configuration loaded from " + CONFIG_FILE);
        } catch (IOException e) {
            logger.warning("Could not load config file, using defaults: " + e.getMessage());
            setDefaults();
        }
    }

    /**
     * 将当前配置信息保存到属性文件中
     */
    public static void saveConfig() {
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
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
}
