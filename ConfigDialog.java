import javax.swing.*;
import java.awt.*;

/**
 * 配置对话框类
 * 提供图形化界面用于修改和保存应用程序的配置参数
 */
public class ConfigDialog extends JDialog {
    // UI 输入控件
    private JTextField serverUrlField;
    private JTextField connectTimeoutField;
    private JTextField readTimeoutField;
    private JCheckBox onlyPdfCheckBox;
    // 标记配置是否已更改
    private boolean configChanged = false;

    /**
     * 构造函数
     * 
     * @param parent 父窗口句柄
     */
    public ConfigDialog(JFrame parent) {
        super(parent, "配置", true);
        initializeUI();
        loadCurrentConfig();
        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * 初始化界面组件布局
     */
    private void initializeUI() {
        setLayout(new BorderLayout(10, 10));

        JPanel mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // 服务器地址输入项
        gbc.gridx = 0;
        gbc.gridy = 0;
        mainPanel.add(new JLabel("服务器地址:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        serverUrlField = new JTextField(30);
        mainPanel.add(serverUrlField, gbc);

        // 连接超时输入项
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        mainPanel.add(new JLabel("连接超时时间设置(秒):"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        connectTimeoutField = new JTextField(5);
        mainPanel.add(connectTimeoutField, gbc);

        // 读写超时输入项
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        mainPanel.add(new JLabel("读写超时时间设置(秒):"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        readTimeoutField = new JTextField(5);
        mainPanel.add(readTimeoutField, gbc);

        // 仅上传 PDF 过滤选项
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        mainPanel.add(new JLabel("文件过滤:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        onlyPdfCheckBox = new JCheckBox("仅上传 PDF 文件");
        mainPanel.add(onlyPdfCheckBox, gbc);

        // 按钮栏
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton saveButton = new JButton("保存");
        JButton cancelButton = new JButton("取消");
        JButton testButton = new JButton("测试");

        // 注册事件监听器
        saveButton.addActionListener(e -> {
            if (saveConfig()) {
                configChanged = true;
                dispose();
            }
        });

        cancelButton.addActionListener(e -> dispose());

        testButton.addActionListener(e -> testConnection());

        buttonPanel.add(saveButton);
        buttonPanel.add(testButton);
        buttonPanel.add(cancelButton);

        add(mainPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        // 设置默认确认按钮
        getRootPane().setDefaultButton(saveButton);
    }

    /**
     * 将当前生效的配置加载到UI界面中
     */
    private void loadCurrentConfig() {
        serverUrlField.setText(AppConfig.getServerUrl());
        connectTimeoutField.setText(String.valueOf(AppConfig.getConnectTimeout()));
        readTimeoutField.setText(String.valueOf(AppConfig.getReadTimeout()));
        onlyPdfCheckBox.setSelected(AppConfig.isOnlyUploadPdf());
    }

    /**
     * 保存UI中的配置到配置文件
     * 
     * @return 是否保存成功
     */
    private boolean saveConfig() {
        try {
            String serverUrl = serverUrlField.getText().trim();
            int connectTimeout = Integer.parseInt(connectTimeoutField.getText().trim());
            int readTimeout = Integer.parseInt(readTimeoutField.getText().trim());

            // 基础校验
            if (serverUrl.isEmpty()) {
                JOptionPane.showMessageDialog(this, "服务器URL不能为空", "错误", JOptionPane.ERROR_MESSAGE);
                return false;
            }

            if (connectTimeout <= 0 || readTimeout <= 0) {
                JOptionPane.showMessageDialog(this, "超时时间必须大于0", "错误", JOptionPane.ERROR_MESSAGE);
                return false;
            }

            // 更新到配置类
            AppConfig.setServerUrl(serverUrl);

            AppConfig.properties.setProperty("timeout.connect", String.valueOf(connectTimeout));
            AppConfig.properties.setProperty("timeout.read", String.valueOf(readTimeout));
            AppConfig.saveConfig();
            AppConfig.setOnlyUploadPdf(onlyPdfCheckBox.isSelected());

            JOptionPane.showMessageDialog(this, "配置已保存并生效", "成功", JOptionPane.INFORMATION_MESSAGE);
            return true;

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "无效的数字格式", "错误", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /**
     * 测试与指定服务器地址的连接情况
     */
    private void testConnection() {
        String serverUrl = serverUrlField.getText().trim();
        if (serverUrl.isEmpty()) {
            JOptionPane.showMessageDialog(this, "服务器URL为空", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            java.net.URL url = new java.net.URL(serverUrl);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode == 200 || responseCode == 404) {
                JOptionPane.showMessageDialog(this,
                        "服务器可访问 (响应码: " + responseCode + ")",
                        "测试成功", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                        "服务器返回异常码: " + responseCode,
                        "测试失败", JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "连接失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 返回配置是否曾被修改过
     */
    public boolean isConfigChanged() {
        return configChanged;
    }
}