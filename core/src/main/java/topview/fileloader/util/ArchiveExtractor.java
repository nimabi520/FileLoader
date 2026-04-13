package topview.fileloader.util;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.*;

/**
 * 压缩包解压工具类
 * 支持 ZIP 格式解压，用于用户直接选择压缩包时自动解压上传
 */
public class ArchiveExtractor {
    private static final Logger logger = Logger.getLogger(ArchiveExtractor.class.getName());

    // 支持的压缩包扩展名
    private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<>(Arrays.asList(
        "zip"
    ));

    /**
     * 检查文件是否为支持的压缩包格式
     */
    public static boolean isArchiveFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String ext = getExtension(fileName).toLowerCase();
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * 解压 ZIP 文件（Java 原生支持）
     * 返回解压后的文件列表
     */
    public static List<File> extractZip(File zipFile, Path targetDir) throws IOException {
        List<File> extractedFiles = new ArrayList<>();

        Files.createDirectories(targetDir);

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                // 处理文件名编码问题（处理中文文件名乱码）
                String entryName = entry.getName();
                Path targetPath = targetDir.resolve(entryName).normalize();

                // 安全检查：确保解压路径在目标目录内（防止 ZIP 路径遍历攻击）
                if (!targetPath.startsWith(targetDir)) {
                    logger.warning("跳过不安全的 ZIP 条目: " + entryName);
                    continue;
                }

                Files.createDirectories(targetPath.getParent());

                try (FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
                extractedFiles.add(targetPath.toFile());
                zis.closeEntry();
            }
        }
        return extractedFiles;
    }

    /**
     * 创建临时解压目录
     */
    public static Path createTempExtractDir(String batchId) throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"),
                                 "fileloader_extract", batchId);
        Files.createDirectories(tempDir);
        return tempDir;
    }

    /**
     * 清理临时解压目录
     */
    public static void cleanupTempDir(Path tempDir) {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "清理临时文件失败: " + p, e);
                    }
                });
        } catch (IOException e) {
            logger.log(Level.WARNING, "清理临时目录失败: " + tempDir, e);
        }
    }

    /**
     * 过滤出实际可上传的文件（排除目录、隐藏文件等）
     */
    public static List<File> filterValidFiles(List<File> files) {
        List<File> validFiles = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && !file.isHidden() && file.canRead()) {
                validFiles.add(file);
            }
        }
        return validFiles;
    }

    private static String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1) : "";
    }
}
