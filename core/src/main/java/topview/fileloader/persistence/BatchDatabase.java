package topview.fileloader.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 本地 SQLite 数据库封装类
 * 持久化存储每个批次的状态记录，程序重启后可恢复历史数据。
 *
 * 数据库文件：~/.fileloader/batches.db
 * 表结构：
 * batch_id TEXT PRIMARY KEY — 批次 ID
 * status TEXT — 最新状态描述
 * created_at TEXT — 首次记录时间
 * updated_at TEXT — 最后更新时间
 */
public class BatchDatabase {

    private static final Logger logger = Logger.getLogger(BatchDatabase.class.getName());
    private static final Path APP_HOME_DIR = Paths.get(System.getProperty("user.home"), ".fileloader");
    private static final Path DB_FILE = APP_HOME_DIR.resolve("batches.db");
    private static final Path LEGACY_DB_FILE = Paths.get("batches.db");
    private static final String JDBC_URL = "jdbc:sqlite:" + DB_FILE.toAbsolutePath();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile boolean dbAvailable = false;

    /** 代表一条批次记录（只读数据载体） */
    public static class BatchRecord {
        public final String batchId;
        public final String status;
        public final String createdAt;
        public final String updatedAt;

        BatchRecord(String batchId, String status, String createdAt, String updatedAt) {
            this.batchId = batchId;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    /**
     * 初始化数据库：加载 JDBC 驱动，建表（若不存在）。
     * 应在程序启动时调用一次。
     */
    public static void init() {
        try {
            // 显式加载 sqlite-jdbc 驱动
            Class.forName("org.sqlite.JDBC");
            dbAvailable = true;
        } catch (ClassNotFoundException e) {
            dbAvailable = false;
            logger.warning("sqlite-jdbc 驱动未找到，已降级为不持久化模式（不影响上传与状态查询）: " + e.getMessage());
            return;
        }

        ensureDataDirectory();
        migrateLegacyDatabaseIfNeeded();

        try (Connection conn = connect();
                Statement stmt = conn.createStatement()) {
            // WAL 模式：减少写锁竞争，提升并发性
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS batch_records (
                            batch_id   TEXT PRIMARY KEY,
                            status     TEXT NOT NULL DEFAULT '',
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                    """);
            logger.info("BatchDatabase 初始化完成，数据库文件: " + DB_FILE.toAbsolutePath());
        } catch (SQLException e) {
            dbAvailable = false;
            logger.log(Level.SEVERE, "BatchDatabase 初始化失败", e);
        }
    }

    /**
     * 插入或更新一条批次记录。
     * 若 batch_id 已存在则仅更新 status 和 updated_at；否则插入新行。
     *
     * @param batchId 批次 ID
     * @param status  当前状态文本
     */
    public static void upsert(String batchId, String status) {
        if (!dbAvailable)
            return;
        String now = LocalDateTime.now().format(FMT);
        String sql = """
                    INSERT INTO batch_records (batch_id, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(batch_id) DO UPDATE SET
                        status     = excluded.status,
                        updated_at = excluded.updated_at
                """;
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, batchId);
            ps.setString(2, status);
            ps.setString(3, now);
            ps.setString(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "BatchDatabase upsert 失败 batchId=" + batchId, e);
        }
    }

    /**
     * 仅更新已有记录的状态（不会插入新行）。
     * 供状态变更回调调用，比 upsert 语义更精确。
     *
     * @param batchId 批次 ID
     * @param status  新状态文本
     */
    public static void updateStatus(String batchId, String status) {
        if (!dbAvailable)
            return;
        String now = LocalDateTime.now().format(FMT);
        String sql = "UPDATE batch_records SET status=?, updated_at=? WHERE batch_id=?";
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, now);
            ps.setString(3, batchId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "BatchDatabase updateStatus 失败 batchId=" + batchId, e);
        }
    }

    /**
     * 查询全部批次记录，按 created_at 倒序排列（最新的在最前）。
     *
     * @return 批次记录列表
     */
    public static List<BatchRecord> getAll() {
        List<BatchRecord> list = new ArrayList<>();
        if (!dbAvailable)
            return list;
        String sql = "SELECT batch_id, status, created_at, updated_at FROM batch_records ORDER BY created_at DESC";
        try (Connection conn = connect();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new BatchRecord(
                        rs.getString("batch_id"),
                        rs.getString("status"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "BatchDatabase getAll 失败", e);
        }
        return list;
    }

    // ---- 内部工具 ----

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(JDBC_URL);
    }

    private static void ensureDataDirectory() {
        try {
            Files.createDirectories(APP_HOME_DIR);
        } catch (IOException e) {
            logger.log(Level.WARNING, "创建数据目录失败: " + APP_HOME_DIR, e);
        }
    }

    private static void migrateLegacyDatabaseIfNeeded() {
        if (!Files.exists(LEGACY_DB_FILE) || Files.exists(DB_FILE)) {
            return;
        }
        try {
            Files.copy(LEGACY_DB_FILE, DB_FILE, StandardCopyOption.COPY_ATTRIBUTES);
            logger.info("已迁移历史数据库到: " + DB_FILE.toAbsolutePath());
        } catch (IOException e) {
            logger.log(Level.WARNING, "迁移历史数据库失败", e);
        }
    }
}
