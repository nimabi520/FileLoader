package topview.fileloader.monitor;

import topview.fileloader.config.AppConfig;
import topview.fileloader.util.ArchiveExtractor;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 文件监控核心类
 * 负责检测指定目录下的文件变化，并在文件就绪后触发上传任务
 */
public class FileMonitor {
    // 日志记录器
    private static final Logger logger = Logger.getLogger(FileMonitor.class.getName());
    // 存储已知文件及其所属目录：文件名 -> 目录路径
    private final ConcurrentHashMap<String, String> existingFiles = new ConcurrentHashMap<>();
    // 存储各文件夹的 WatchService 监控任务
    private final ConcurrentHashMap<Path, WatchService> watchServices = new ConcurrentHashMap<>();
    // 记录当前正在检查稳定性的文件，防止重复处理
    private final ConcurrentHashMap<Path, Boolean> checkingFiles = new ConcurrentHashMap<>();
    // 用于执行监控和文件检查任务的线程池
    private final ExecutorService executorService = Executors.newFixedThreadPool(5);
    // UI 句柄，用于输出日志和更新状态
    private final MonitorCallbacks ui;
    // 停机标志位
    private volatile boolean isShutdown = false;

    /**
     * 构造函数
     * 
     * @param ui 主界面对象
     */
    public FileMonitor(MonitorCallbacks ui) {
        this.ui = ui;
    }

    /**
     * 开始监控指定的文件夹
     * 
     * @param folder 文件夹路径
     */
    public void startMonitoring(Path folder) {
        // 如果已在监控列表，则跳过
        if (watchServices.containsKey(folder)) {
            logger.warning("Folder already being monitored: " + folder);
            ui.addLog("文件夹已在监控: " + folder);
            return;
        }

        // 提交后台异步监控任务
        executorService.submit(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                // 注册创建和删除文件事件
                folder.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watchServices.put(folder, watchService);

                // 初始化：扫描文件夹中已有的文件并触发上传
                Set<Path> existingFilePaths = new HashSet<>();
                Files.walk(folder, 1)
                        .filter(Files::isRegularFile)
                        .forEach(path -> {
                            existingFiles.put(path.getFileName().toString(), folder.toString());
                            existingFilePaths.add(path);
                            logger.info("Initialized file: " + path.getFileName());
                            ui.addLog("发现现有文件: " + path.getFileName());
                        });

                logger.info("Started monitoring folder: " + folder);
                ui.addLog("开始监控文件夹: " + folder);

                // 重置该文件夹的批次绑定，确保使用全新批次
                ui.resetFolderBatch(folder.toString());

                // 处理所有现有文件的上传
                if (!existingFilePaths.isEmpty()) {
                    ui.addLog("开始检测并上传现有文件，共 " + existingFilePaths.size() + " 个文件");
                    existingFilePaths.forEach(path -> handleNewFile(path, folder));
                }

                // 监控主循环
                while (!isShutdown && !Thread.currentThread().isInterrupted()) {
                    WatchKey key;
                    try {
                        // 阻塞直到有新事件
                        key = watchService.take();
                    } catch (InterruptedException e) {
                        logger.info("Monitoring interrupted for " + folder);
                        ui.addLog("监控中断: " + folder);
                        Thread.currentThread().interrupt();
                        break;
                    }

                    Set<Path> newFiles = new HashSet<>();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path fileName = (Path) event.context();
                        String file = fileName.toString();
                        String folderKey = folder.toString();

                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            // 检查文件是否为新文件且未被处理
                            if (!existingFiles.containsKey(file) || !folderKey.equals(existingFiles.get(file))) {
                                Path fullPath = folder.resolve(fileName);
                                if (!checkingFiles.containsKey(fullPath)) {
                                    newFiles.add(fullPath);
                                    existingFiles.put(file, folderKey);
                                    logger.info("New file detected: " + fullPath);
                                    ui.addLog("检测到新文件: " + fullPath);
                                } else {
                                    logger.info("File already being checked: " + fileName);
                                    ui.addLog("文件正在检查中，跳过: " + fileName);
                                }
                            } else {
                                logger.info("File already exists: " + fileName);
                                ui.addLog("文件已存在，跳过: " + fileName);
                            }
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            // 如果文件被删除，从已有文件列表中移除，以便重新添加时能被再次检测
                            if (existingFiles.remove(file) != null) {
                                logger.info("File deleted, removed from tracking: " + fileName);
                                ui.addLog("文件被删除，已移除跟踪: " + fileName);
                            }
                        }
                    }
                    // 批量处理新文件
                    if (!newFiles.isEmpty()) {
                        Thread.sleep(1000); // 延迟1秒收集更多连续事件
                        newFiles.forEach(path -> handleNewFile(path, folder));
                    }
                    // 重置监控键，若失效则退出
                    if (!key.reset()) {
                        logger.warning("Watch key invalid for folder: " + folder);
                        ui.addLog("监控键失效: " + folder);
                        break;
                    }
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "IO Error in monitoring " + folder, e);
                ui.updateStatus("监控错误: " + e.getMessage());
                ui.addLog("监控错误: " + folder + " - " + e.getMessage());
            } catch (InterruptedException e) {
                logger.info("Monitoring interrupted for " + folder);
                ui.addLog("监控中断: " + folder);
                Thread.currentThread().interrupt();
            } finally {
                // 清理工作
                watchServices.remove(folder);
                ui.addLog("停止监控文件夹: " + folder);
            }
        });
    }

    /**
     * 处理检测到的新文件，包含就绪检查（防止文件还在写入中就被上传）
     * 
     * @param filePath   文件物理路径
     * @param folderPath 文件所属的监控文件夹路径
     */
    private void handleNewFile(Path filePath, Path folderPath) {
        // 原子性检查并设置处理状态
        if (checkingFiles.putIfAbsent(filePath, true) != null) {
            logger.info("File already being processed: " + filePath);
            ui.addLog("文件已在处理中，跳过: " + filePath);
            return;
        }

        // 忽略 Windows 临时文件等
        if (filePath.getFileName().toString().startsWith("~$")) {
            logger.info("Skipping temporary file: " + filePath);
            ui.addLog("跳过临时文件: " + filePath);
            checkingFiles.remove(filePath);
            return;
        }

        // PDF 过滤：如果开启"仅上传 PDF"，则跳过非 PDF 文件
        if (AppConfig.isOnlyUploadPdf()) {
            String fileName = filePath.getFileName().toString().toLowerCase();
            if (!fileName.endsWith(".pdf")) {
                logger.info("Skipping non-PDF file (filter enabled): " + filePath);
                ui.addLog("跳过非 PDF 文件（过滤已启用）: " + filePath.getFileName());
                checkingFiles.remove(filePath);
                return;
            }
        }

        // 压缩包过滤：文件夹监控中发现的压缩包直接跳过（用户主动选择的压缩包才会解压）
        if (ArchiveExtractor.isArchiveFile(filePath.getFileName().toString())) {
            logger.info("Skipping archive file in folder monitoring: " + filePath);
            ui.addLog("跳过压缩包（请在添加路径时直接选择压缩包以解压上传）: " + filePath.getFileName());
            checkingFiles.remove(filePath);
            return;
        }

        // 在单独的线程中检查文件是否写入完毕
        executorService.submit(() -> {
            try {
                logger.info("Checking file readiness: " + filePath);
                ui.addLog("检查文件就绪状态: " + filePath);

                // 根据文件大小决定检查次数
                int maxAttempts = Files.size(filePath) > 5_000_000 ? 20 : 10;
                int stableCount = 0;
                long lastSize = -1;
                int existenceRetries = 5;

                for (int i = 0; i < maxAttempts; i++) {
                    // 处理文件可能被锁定或临时消失的情况
                    if (!Files.exists(filePath)) {
                        if (existenceRetries > 0) {
                            logger.warning("File does not exist, retrying (" + existenceRetries + " attempts left): "
                                    + filePath);
                            ui.addLog("文件不存在，重试 (" + existenceRetries + " 次剩余): " + filePath);
                            existenceRetries--;
                            Thread.sleep(1000);
                            continue;
                        }
                        logger.warning("File no longer exists after retries: " + filePath);
                        ui.addLog("文件不再存在: " + filePath);
                        return;
                    }

                    if (!Files.isReadable(filePath)) {
                        logger.warning("File not readable, retrying: " + filePath);
                        ui.addLog("文件不可读，重试: " + filePath);
                        Thread.sleep(1000);
                        continue;
                    }

                    long currentSize = Files.size(filePath);
                    logger.info("File size check [" + (i + 1) + "/" + maxAttempts + "]: " + filePath + ", size: "
                            + currentSize);
                    ui.addLog("文件大小检查 [" + (i + 1) + "/" + maxAttempts + "]: " + filePath + ", 大小: " + currentSize);

                    // 如果文件大小连续3次保持不变，认为文件已就绪
                    if (currentSize == lastSize && currentSize > 0) {
                        stableCount++;
                        if (stableCount >= 3) {
                            break;
                        }
                    } else {
                        lastSize = currentSize;
                        stableCount = 0;
                    }
                    Thread.sleep(1000);
                }

                if (stableCount >= 3 && Files.exists(filePath) && Files.isReadable(filePath)
                        && Files.size(filePath) > 0) {
                    logger.info("File ready for upload: " + filePath);
                    ui.addLog("文件就绪，开始上传: " + filePath);
                    ui.uploadFile(filePath.toString(), folderPath.toString());
                } else {
                    logger.warning("File not ready or empty: " + filePath);
                    ui.addLog("文件未就绪或为空: " + filePath);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("File check interrupted: " + filePath);
                ui.addLog("文件检查中断: " + filePath);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error checking file: " + filePath, e);
                ui.addLog("检查文件错误: " + filePath + " - " + e.getMessage());
            } finally {
                // 确保移除检查标记
                checkingFiles.remove(filePath);
            }
        });
    }

    /**
     * 停止监控指定的文件夹
     * 
     * @param folder 文件夹路径
     */
    public void stopMonitoring(Path folder) {
        WatchService watchService = watchServices.remove(folder);
        if (watchService != null) {
            try {
                watchService.close(); // 关闭服务会触发 take() 抛出异常从而退出循环
                logger.info("Stopped monitoring folder: " + folder);
                ui.addLog("停止监控文件夹: " + folder);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error closing watch service for " + folder, e);
                ui.addLog("关闭监控服务错误: " + folder + " - " + e.getMessage());
            }
        }
    }

    /**
     * 系统关机，安全清理所有资源
     */
    public void shutdown() {
        isShutdown = true;

        // 关闭所有已启动的监控服务
        watchServices.forEach((folder, watchService) -> {
            try {
                watchService.close();
                logger.info("Closed watch service for " + folder);
                ui.addLog("关闭监控服务: " + folder);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error closing watch service for " + folder, e);
                ui.addLog("关闭监控服务错误: " + folder + " - " + e.getMessage());
            }
        });
        watchServices.clear();

        // 停止工作线程池（立即中断，不阻塞调用线程）
        executorService.shutdownNow();
        new Thread(() -> {
            try {
                if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
                    logger.warning("Executor service forced shutdown");
                    ui.addLog("强制关闭监控线程池");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "FileMonitor-Shutdown").start();
    }

    /**
     * 检查某个文件夹是否正在被监控
     */
    public boolean isMonitoring(Path folder) {
        return watchServices.containsKey(folder);
    }
}
