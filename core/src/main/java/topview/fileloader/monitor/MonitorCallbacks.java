package topview.fileloader.monitor;

/**
 * 前端回调接口。
 *
 * FileMonitor 等后台组件通过该接口向前端发送日志、状态和上传通知，
 * 以便在 Swing 与 Compose 两种 UI 间复用同一套业务逻辑。
 */
public interface MonitorCallbacks {
    void updateStatus(String status);

    void addLog(String message);

    void uploadFile(String filePath);

    default void uploadFile(String filePath, String folderPath) {
        uploadFile(filePath);
    }

    default void resetFolderBatch(String folderPath) {
        // Compose implementation does not require per-folder batch reset.
    }
}
