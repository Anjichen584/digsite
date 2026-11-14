package com.digsite.achieve;

import com.digsite.util.YamlIo;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 成就存档（achieve-store.yml）：每玩家已达成成就 id 集 + 全局/复合键计数器，脏标记 + flush 原子写。
 *
 * <p>计数器同时承担「参观计数」「无损累计」「common 全集进度」等通用 KV 职责（key 由调用方约定）：
 * <ul>
 *   <li>{@code visit:&lt;exhibitId&gt;} —— 展品参观次数（E3）；</li>
 *   <li>{@code perfect:&lt;uuid&gt;} —— 玩家累计无损修复件数；</li>
 *   <li>{@code common:&lt;uuid&gt;:&lt;artifactId&gt;} —— 玩家修复过的 common 文物集。</li>
 * </ul>
 */
public final class AchieveStore {
    private final File file;
    private final Map<UUID, Set<String>> achieved = new LinkedHashMap<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private volatile boolean dirty = false;

    public AchieveStore(File dataFolder) {
        this.file = new File(dataFolder, "achieve-store.yml");
        load();
    }

    private void load() {
        YamlConfiguration yml = YamlIo.load(file);
        ConfigurationSection aSec = yml.getConfigurationSection("achieved");
        if (aSec != null) {
            for (String key : aSec.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    achieved.put(uuid, new LinkedHashSet<>(aSec.getStringList(key)));
                } catch (IllegalArgumentException ignored) {
                    // 非法 UUID key：跳过该条
                }
            }
        }
        ConfigurationSection cSec = yml.getConfigurationSection("counters");
        if (cSec != null) {
            for (String key : cSec.getKeys(false)) {
                counters.put(key, cSec.getLong(key, 0L));
            }
        }
    }

    // ---------- 成就达成集 ----------

    /**
     * 标记达成；幂等——首次达成返回 true（调用方弹 Title/公告），已达成返回 false 不重复播报。
     */
    public boolean mark(UUID uuid, String achieveId) {
        if (uuid == null || achieveId == null || achieveId.isBlank()) return false;
        Set<String> set = achieved.computeIfAbsent(uuid, k -> new LinkedHashSet<>());
        if (!set.add(achieveId)) return false;
        dirty = true;
        return true;
    }

    /** 是否已达成。 */
    public boolean has(UUID uuid, String achieveId) {
        Set<String> set = achieved.get(uuid);
        return set != null && set.contains(achieveId);
    }

    /** 该玩家已达成成就 id 集（副本）。 */
    public Set<String> of(UUID uuid) {
        Set<String> set = achieved.get(uuid);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    // ---------- 通用计数器 ----------

    /** 计数器 +1，返回自增后的值。 */
    public long bumpCounter(String key) {
        long v = counters.getOrDefault(key, 0L) + 1L;
        counters.put(key, v);
        dirty = true;
        return v;
    }

    /** 读计数器；不存在返回 0。 */
    public long getCounter(String key) {
        return counters.getOrDefault(key, 0L);
    }

    // ---------- 冲刷 ----------

    public void flush() {
        if (!dirty) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> e : achieved.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<String> ids = new ArrayList<>(e.getValue());
            yml.set("achieved." + e.getKey(), ids);
        }
        for (Map.Entry<String, Long> e : counters.entrySet()) {
            yml.set("counters." + e.getKey(), e.getValue());
        }
        if (YamlIo.trySave(yml, file)) dirty = false;
    }
}
