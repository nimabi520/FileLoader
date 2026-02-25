import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 监控程序主界面类
 * 负责 UI 初始化、事件处理以及管理上传队列和工作线程
 */
public class MonitorUI {
    // 存储当前正在监控的文件夹路径
    private final ConcurrentHashMap<Path, Boolean> monitoredFolders = new ConcurrentHashMap<>();
    private static final Logger logger = Logger.getLogger(MonitorUI.class.getName());
    
    // UI 组件
    private JTextArea folderListArea;  // 显示监控文件夹列表
    private JTextArea logArea;         // 显示操作日志
    private JLabel statusLabel;        // 底部状态栏标签
    private DefaultTableModel uploadTableModel; // 上传记录表模型
    private final Map<String, JProgressBar> progressBars = new ConcurrentHashMap<>(); // 文件名 -> 进度条
    
    // 业务模块
    private final FileMonitor fileMonitor;
    
    // 内部类：封装上传任务信息
    private static class UploadTask {
        final File file;
        final String batchId;
        
        UploadTask(File file, String batchId) {
            this.file = file;
            this.batchId = batchId;
        }
    }

    // 上传文件队列，用于平滑处理高频新文件
    private final BlockingQueue<UploadTask> uploadQueue = new LinkedBlockingQueue<>();
    // 固定大小的上传线程池
    private final ExecutorService uploadExecutor = Executors.newFixedThreadPool(3);
    
    // 批次控制
    private volatile String currentBatchId = null;
    private volatile long batchStartTime = 0;
    
    // 承载动态添加的进度条面板
    private final JPanel progressPanel;

    /**
     * 构造函数，初始化 UI 并启动上传工作线程
     */
    public MonitorUI() {
        this.fileMonitor = new FileMonitor(this);
        this.progressPanel = new JPanel();
        initializeUI();
        startUploadWorker();
    }

    /**
     * 启动固定数量的上传工作线程，从队列中消费文件
     */
    private void startUploadWorker() {
        for (int i = 0; i < 3; i++) {
            uploadExecutor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        // 阻塞式获取待上传文件
                        UploadTask task = uploadQueue.take();
                        performUpload(task);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Upload worker error", e);
                        addLog("上传工作线程错误: " + e.getMessage());
                    }
                }
            });
        }
    }

    /**
     * 初始化 Swing 界面、布局和事件处理
     */
    private void initializeUI() {
        JFrame frame = new JFrame("多文件夹监控上传工具");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);
        frame.setLocationRelativeTo(null);

        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 创建各功能面板
        JPanel inputPanel = createInputPanel(frame);
        JPanel listPanel = createListPanel();
        JPanel logPanel = createLogPanel();
        JPanel monitorPanel = createMonitorPanel();
        JPanel statusPanel = createStatusPanel();

        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));
        progressPanel.setBorder(BorderFactory.createTitledBorder("上传进度"));
        JScrollPane progressScrollPane = new JScrollPane(progressPanel);

        // 右侧使用选项卡面板切换日志和监控记录
        JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.addTab("监控记录", monitorPanel);
        rightTabs.addTab("系统日志", logPanel);

        // 使用 JSplitPane 实现左右可拉伸布局
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, rightTabs);
        splitPane.setDividerLocation(300); 
        splitPane.setResizeWeight(0.3); 
        splitPane.setContinuousLayout(true); 
        listPanel.setMinimumSize(new Dimension(200, 0)); 
        // logPanel.setMinimumSize(new Dimension(200, 0)); // No longer direct child of splitPane 

        panel.add(inputPanel, BorderLayout.NORTH);
        panel.add(splitPane, BorderLayout.CENTER); 
        panel.add(progressScrollPane, BorderLayout.SOUTH);
        panel.add(statusPanel, BorderLayout.SOUTH);

        frame.add(panel);
        frame.setVisible(true);

        // 窗口关闭监听：执行优雅关机，尝试处理完剩余队列
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                fileMonitor.shutdown();
                uploadExecutor.shutdown();
                try {
                    if (!uploadQueue.isEmpty()) {
                        addLog("等待队列清空，剩余文件: " + uploadQueue.size());
                        uploadExecutor.awaitTermination(30, TimeUnit.SECONDS);
                    }
                    if (!uploadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        uploadExecutor.shutdownNow();
                    }
                } catch (InterruptedException ex) {
                    uploadExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                System.exit(0);
            }
        });
    }

    /**
     * 创建顶部输入和操作面板
     */
    private JPanel createInputPanel(JFrame frame) {
        JPanel inputPanel = new JPanel(new GridLayout(3, 2, 10, 10));
        JTextField folderPathField = new JTextField(20);
        JButton addButton = new JButton("添加文件夹");
        JButton removeButton = new JButton("移除选中文件夹");
        JButton browseButton = new JButton("浏览...");
        JButton configButton = new JButton("配置");
        JButton diagnoseButton = new JButton("诊断");

        // 事件响应：添加文件夹
        addButton.addActionListener(e -> {
            String path = folderPathField.getText().trim();
            if (!path.isEmpty()) {
                addFolder(Paths.get(path));
                folderPathField.setText("");
            }
        });

        // 事件响应：移除文件夹提示
        removeButton.addActionListener(e -> {
            String selected = JOptionPane.showInputDialog(frame, "输入要移除的文件夹路径:");
            if (selected != null && !selected.trim().isEmpty()) {
                removeFolder(Paths.get(selected.trim()));
            }
        });

        // 浏览对话框
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("选择要监控的文件夹");
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                String path = chooser.getSelectedFile().getAbsolutePath();
                folderPathField.setText(path);
            }
        });

        // 打开配置对话框
        configButton.addActionListener(e -> {
            ConfigDialog dialog = new ConfigDialog(frame);
            dialog.setVisible(true);
            if (dialog.isConfigChanged()) {
                addLog("配置已更新");
            }
        });

        // 启动独立线程进行服务诊断
        diagnoseButton.addActionListener(e -> {
            new Thread(() -> {
                addLog("开始服务器诊断...");
                ServerDiagnostic.diagnoseServer(AppConfig.getServerUrl());
                addLog("服务器诊断完成，请查看控制台输出");
            }).start();
        });

        inputPanel.add(new JLabel("文件夹路径:"));
        inputPanel.add(folderPathField);
        inputPanel.add(addButton);
        inputPanel.add(browseButton);
        inputPanel.add(removeButton);
        inputPanel.add(configButton);
        inputPanel.add(diagnoseButton);

        return inputPanel;
    }

    /**
     * 创建显示监控路径的面板
     */
    private JPanel createListPanel() {
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.setBorder(BorderFactory.createTitledBorder("监控文件夹列表"));

        folderListArea = new JTextArea(10, 30);
        folderListArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(folderListArea);
        listPanel.add(scrollPane, BorderLayout.CENTER);

        return listPanel;
    }

    /**
     * 创建上传监控面板（表格）
     */
    private JPanel createMonitorPanel() {
        JPanel monitorPanel = new JPanel(new BorderLayout());
        
        String[] columnNames = {"时间", "文件名", "批次ID", "状态"};
        uploadTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(uploadTableModel);
        // 设置列宽
        table.getColumnModel().getColumn(0).setPreferredWidth(150); // 时间
        table.getColumnModel().getColumn(1).setPreferredWidth(200); // 文件名
        table.getColumnModel().getColumn(2).setPreferredWidth(100); // 批次ID
        table.getColumnModel().getColumn(3).setPreferredWidth(100); // 状态
        
        // 添加右键菜单
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem downloadBatchItem = new JMenuItem("下载批次文件");
        JMenuItem downloadWrongItem = new JMenuItem("下载异常文件");
        
        downloadBatchItem.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                String batchId = (String) table.getValueAt(selectedRow, 2);
                downloadBatchFiles(batchId, false);
            }
        });
        
        downloadWrongItem.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                String batchId = (String) table.getValueAt(selectedRow, 2);
                downloadBatchFiles(batchId, true);
            }
        });
        
        popupMenu.add(downloadBatchItem);
        popupMenu.add(downloadWrongItem);
        table.setComponentPopupMenu(popupMenu);
        
        JScrollPane scrollPane = new JScrollPane(table);
        monitorPanel.add(scrollPane, BorderLayout.CENTER);
        return monitorPanel;
    }

    /**
     * 下载批次文件
     */
    private void downloadBatchFiles(String batchId, boolean withWrong) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择保存目录");
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            File saveDir = chooser.getSelectedFile();
            addLog("开始下载批次 " + batchId + (withWrong ? " 的异常文件" : " 的文件") + "...");
            new Thread(() -> {
                String result = DownloadService.downloadBatch(batchId, saveDir, withWrong);
                addLog(result);
                updateStatus(result);
            }).start();
        }
    }

    /**
     * 创建操作日志显示面板
     */
    private JPanel createLogPanel() {
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("操作日志"));
        logPanel.setPreferredSize(new Dimension(400, 0));

        logArea = new JTextArea(20, 35);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logPanel.add(scrollPane, BorderLayout.CENTER);

        return logPanel;
    }

    /**
     * 创建状态栏面板
     */
    private JPanel createStatusPanel() {
        JPanel statusPanel = new JPanel(new BorderLayout());

        statusLabel = new JLabel("状态: 等待监控...");
        statusPanel.add(statusLabel, BorderLayout.WEST);

        return statusPanel;
    }

    /**
     * 将文件夹加入监控并启动监控任务
     */
    private void addFolder(Path folder) {
        if (Files.exists(folder) && Files.isDirectory(folder)) {
            if (monitoredFolders.putIfAbsent(folder, true) == null) {
                updateFolderList();
                fileMonitor.startMonitoring(folder);
                updateStatus("状态: 成功添加文件夹 " + folder);
                addLog("添加监控文件夹: " + folder);
            } else {
                updateStatus("状态: 文件夹已存在 " + folder);
            }
        } else {
            JOptionPane.showMessageDialog(null, "无效的文件夹路径！", "错误", JOptionPane.ERROR_MESSAGE);
            updateStatus("状态: 无效路径 " + folder);
        }
    }

    /**
     * 移除监控文件夹
     */
    private void removeFolder(Path path) {
        if (monitoredFolders.remove(path) != null) {
            fileMonitor.stopMonitoring(path);
            updateFolderList();
            updateStatus("状态: 成功移除文件夹 " + path);
            addLog("移除监控文件夹: " + path);
        } else {
            updateStatus("状态: 文件夹未找到 " + path);
        }
    }

    /**
     * 刷新左侧文件夹列表显示
     */
    public void updateFolderList() {
        SwingUtilities.invokeLater(() -> {
            folderListArea.setText("");
            monitoredFolders.keySet().forEach(folder ->
                    folderListArea.append(folder.toString() + "\n"));
        });
    }

    /**
     * 更新状态栏文字
     */
    public void updateStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null) {
                statusLabel.setText(status);
            }
        });
    }

    /**
     * 向日志区域追加一条记录
     */
    public void addLog(String message) {
        SwingUtilities.invokeLater(() -> {
            if (logArea != null) {
                logArea.append("[" + java.time.LocalTime.now() + "] " + message + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }

    /**
     * 提供给 FileMonitor 调用，通知有文件需要上传并加入队列
     */
    public void uploadFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            logger.warning("File not found or invalid: " + filePath);
            updateStatus("错误: 文件不存在 " + filePath);
            addLog("错误: 文件不存在 " + filePath);
            return;
        }
        
        String batchId;
        synchronized (this) {
            long now = System.currentTimeMillis();
            // 如果在5秒内有新的文件到来，则重用当前的batchId
            if (currentBatchId == null || (now - batchStartTime > 5000)) {
                currentBatchId = String.valueOf(now);
                batchStartTime = now;
            }
            batchId = currentBatchId;
        }

        UploadTask task = new UploadTask(file, batchId);
        if (uploadQueue.offer(task)) {
            addLog("已加入上传队列: " + file.getName() + " (批次: " + batchId + ", 队列大小: " + uploadQueue.size() + ")");
        } else {
            addLog("加入上传队列失败: " + file.getName());
            logger.warning("Failed to add file to upload queue: " + filePath);
        }
    }

    /**
     * 更新表格中的上传状态
     * 同时也匹配文件名，以防止同一批次中多个文件状态更新错误
     */
    private void updateTableStatus(String batchId, String fileName, String status) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < uploadTableModel.getRowCount(); i++) {
                String rowBatchId = (String) uploadTableModel.getValueAt(i, 2);
                String rowFileName = (String) uploadTableModel.getValueAt(i, 1);
                
                // 必须同时匹配批次ID和文件名
                if (batchId.equals(rowBatchId) && fileName.equals(rowFileName)) {
                    // 如果已经是终态（成功/失败），且新状态也是终态，可能需要考虑是否覆盖？
                    // 这里简化逻辑：直接更新
                    uploadTableModel.setValueAt(status, i, 3);
                    break;
                }
            }
        });
    }

    /**
     * 执行具体的文件上传操作，负责维护进度条 UI
     */
    private void performUpload(UploadTask task) {
        File file = task.file;
        String fileName = file.getName();
        String batchId = task.batchId;
        String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        // 添加记录到表格
        SwingUtilities.invokeLater(() -> {
            uploadTableModel.addRow(new Object[]{timeStr, fileName, batchId, "上传中..."});
        });

        // 动态创建进度条组件
        JProgressBar progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true); // 初始为不确定状态
        JLabel fileLabel = new JLabel(fileName);
        JPanel fileProgressPanel = new JPanel(new BorderLayout());
        fileProgressPanel.add(fileLabel, BorderLayout.WEST);
        fileProgressPanel.add(progressBar, BorderLayout.CENTER);
        
        SwingUtilities.invokeLater(() -> {
            progressPanel.add(fileProgressPanel);
            progressPanel.revalidate();
            progressBars.put(fileName, progressBar);
        });

        try {
            addLog("开始上传文件: " + fileName);
            updateStatus("正在上传: " + fileName);

            // 调用上传服务，并注册进度回调
            UploadResponse response = ProgressUploadService.uploadFile(file, batchId, progress -> {
                SwingUtilities.invokeLater(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(progress);
                    progressBar.setString(progress + "%");
                    updateStatus("正在上传: " + fileName + " (" + progress + "%)");
                });
            });

            // 处理结果
            if (response.getCode() == 200) {
                logger.info("Upload successful: " + fileName);
                updateStatus("上传成功: " + fileName);
                addLog("上传成功: " + fileName + " - " + response.getMessage());
                updateTableStatus(batchId, fileName, "成功");
            } else {
                logger.warning("Upload failed: " + fileName + ", code: " + response.getCode());
                updateStatus("上传失败: " + fileName);
                addLog("上传失败: " + fileName + " - " + response.getMessage());
                updateTableStatus(batchId, fileName, "失败");
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Upload error for " + file.getPath(), e);
            updateStatus("上传错误: " + fileName);
            addLog("上传错误: " + fileName + " - " + e.getMessage());
            updateTableStatus(batchId, fileName, "错误");
        } finally {
            // 清理进度条 UI
            SwingUtilities.invokeLater(() -> {
                progressBars.remove(fileName);
                progressPanel.remove(fileProgressPanel);
                progressPanel.revalidate();
                progressPanel.repaint();
            });
            addLog("当前队列大小: " + uploadQueue.size());
        }
    }

    /**
     * 应用程序入口
     */
    public static void main(String[] args) {
        try {
            // 使用系统原生外观
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            logger.warning("Failed to set system look and feel: " + e.getMessage());
        }

        // 启动主界面
        SwingUtilities.invokeLater(MonitorUI::new);
    }
}
