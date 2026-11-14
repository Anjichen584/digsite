package com.digsite.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 唯一允许的存档入口：tmp → fsync → ATOMIC_MOVE 原子写；
 * 读档失败时把坏档改名 .corrupt-<时间戳> 留档，再返回全新配置（绝不用空档覆盖原档）。
 */
public final class YamlIo {
    private static final Logger LOG = Logger.getLogger("DigSite");

    private YamlIo() {}

    /** 原子写。写盘失败抛 IOException（调用方决定回滚内存还是报错），绝不静默吞掉。 */
    public static void save(YamlConfiguration yml, File file) throws IOException {
        Path target = file.toPath();
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        byte[] bytes = yml.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            ch.force(true);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            // 某些文件系统不支持同目录 ATOMIC_MOVE 的替换语义，退化为普通替换
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 读档。文件不存在返回空配置；损坏时留档 .corrupt-<ts> 并返回空配置。 */
    public static YamlConfiguration load(File file) {
        YamlConfiguration yml = new YamlConfiguration();
        if (!file.exists()) return yml;
        try {
            yml.load(file);
            return yml;
        } catch (Exception corrupted) {
            File keep = new File(file.getParentFile(),
                    file.getName() + ".corrupt-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                            .withZone(ZoneId.systemDefault()).format(Instant.now()));
            try {
                Files.move(file.toPath(), keep.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOG.log(Level.SEVERE, "存档损坏，已留档 " + keep.getName() + "，按空档处理: " + file.getName(), corrupted);
            } catch (IOException moveFail) {
                LOG.log(Level.SEVERE, "存档损坏且留档失败（原档保留）: " + file.getName(), moveFail);
            }
            return new YamlConfiguration();
        }
    }

    /** 便捷写：失败只记日志但向调用方返回 false，供「先落盘后发奖」类逻辑判断。 */
    public static boolean trySave(YamlConfiguration yml, File file) {
        try {
            save(yml, file);
            return true;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "存档写盘失败: " + file.getName(), e);
            return false;
        }
    }
}
