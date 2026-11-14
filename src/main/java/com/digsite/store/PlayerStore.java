package com.digsite.store;

import com.digsite.model.Model;
import com.digsite.util.YamlIo;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 玩家统计存档（players-store.yml）：纯计数（碎片/修复/捐献/清剿/手速），脏标记 + flush 原子写。 */
public final class PlayerStore {
    private final File file;
    private final Map<UUID, Model.PlayerStats> players = new LinkedHashMap<>();
    private volatile boolean dirty = false;

    public PlayerStore(File dataFolder) {
        this.file = new File(dataFolder, "players-store.yml");
        load();
    }

    private void load() {
        YamlConfiguration yml = YamlIo.load(file);
        ConfigurationSection sec = yml.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection p = sec.getConfigurationSection(key);
                if (p == null) continue;
                players.put(uuid, new Model.PlayerStats(
                        uuid,
                        p.getString("name", key),
                        p.getInt("fragments", 0),
                        p.getInt("restored", 0),
                        p.getInt("donations", 0),
                        p.getInt("sites-cleared", 0),
                        p.getInt("hasty", 0)));
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID key：跳过该条
            }
        }
    }

    // ---------- 查询 ----------

    /** 取玩家统计；不存在则创建零值档案（name 用于记录最近名字）。 */
    public Model.PlayerStats stats(UUID uuid, String name) {
        Model.PlayerStats s = players.get(uuid);
        if (s == null) {
            s = new Model.PlayerStats(uuid, name == null ? "?" : name, 0, 0, 0, 0, 0);
            players.put(uuid, s);
            dirty = true;
        } else if (name != null && !name.isBlank() && !name.equals(s.name())) {
            s = new Model.PlayerStats(s.uuid(), name, s.fragmentsFound(), s.artifactsRestored(),
                    s.donations(), s.sitesCleared(), s.hastyHits());
            players.put(uuid, s);
            dirty = true;
        }
        return s;
    }

    // ---------- 计数（UUID + name 版本） ----------

    public void bumpFragments(UUID uuid, String name, int n) {
        Model.PlayerStats s = stats(uuid, name);
        players.put(uuid, new Model.PlayerStats(s.uuid(), s.name(), s.fragmentsFound() + n,
                s.artifactsRestored(), s.donations(), s.sitesCleared(), s.hastyHits()));
        dirty = true;
    }

    public void bumpRestored(UUID uuid, String name, int n) {
        Model.PlayerStats s = stats(uuid, name);
        players.put(uuid, new Model.PlayerStats(s.uuid(), s.name(), s.fragmentsFound(),
                s.artifactsRestored() + n, s.donations(), s.sitesCleared(), s.hastyHits()));
        dirty = true;
    }

    public void bumpDonations(UUID uuid, String name, int n) {
        Model.PlayerStats s = stats(uuid, name);
        players.put(uuid, new Model.PlayerStats(s.uuid(), s.name(), s.fragmentsFound(),
                s.artifactsRestored(), s.donations() + n, s.sitesCleared(), s.hastyHits()));
        dirty = true;
    }

    public void bumpSitesCleared(UUID uuid, String name, int n) {
        Model.PlayerStats s = stats(uuid, name);
        players.put(uuid, new Model.PlayerStats(s.uuid(), s.name(), s.fragmentsFound(),
                s.artifactsRestored(), s.donations(), s.sitesCleared() + n, s.hastyHits()));
        dirty = true;
    }

    public void bumpHasty(UUID uuid, String name, int n) {
        Model.PlayerStats s = stats(uuid, name);
        players.put(uuid, new Model.PlayerStats(s.uuid(), s.name(), s.fragmentsFound(),
                s.artifactsRestored(), s.donations(), s.sitesCleared(), s.hastyHits() + n));
        dirty = true;
    }

    // ---------- 便捷重载（Player / 纯名字） ----------

    /** 在线玩家：手速惩罚 +1。 */
    public void bumpHasty(Player player) {
        bumpHasty(player.getUniqueId(), player.getName(), 1);
    }

    /** 在线玩家：拾取碎片 +n。 */
    public void bumpFragments(Player player, int n) {
        bumpFragments(player.getUniqueId(), player.getName(), n);
    }

    /** 按名字 upsert 玩家档案并累加清剿数（离线/事件回调无 UUID 时使用）。 */
    public void bumpSitesCleared(String playerName) {
        UUID uuid = resolveOrCreate(playerName);
        bumpSitesCleared(uuid, playerName, 1);
    }

    /** 按名字找已有档案；找不到则生成稳定 UUID（不随机，保证 upsert 幂等）。 */
    private UUID resolveOrCreate(String playerName) {
        if (playerName == null) playerName = "?";
        for (Model.PlayerStats s : players.values()) {
            if (playerName.equalsIgnoreCase(s.name())) return s.uuid();
        }
        return UUID.nameUUIDFromBytes(("digsite:" + playerName.toLowerCase()).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 榜单 ----------

    /** 捐献榜前 n 名（donations 降序）。 */
    public List<Model.PlayerStats> topDonors(int n) {
        List<Model.PlayerStats> list = new ArrayList<>(players.values());
        list.sort(Comparator.comparingInt(Model.PlayerStats::donations).reversed()
                .thenComparing(s -> s.name() == null ? "" : s.name()));
        if (n < 0) n = 0;
        return list.subList(0, Math.min(n, list.size()));
    }

    // ---------- 冲刷 ----------

    public void flush() {
        if (!dirty) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Model.PlayerStats s : players.values()) {
            String key = s.uuid().toString();
            yml.set("players." + key + ".name", s.name());
            yml.set("players." + key + ".fragments", s.fragmentsFound());
            yml.set("players." + key + ".restored", s.artifactsRestored());
            yml.set("players." + key + ".donations", s.donations());
            yml.set("players." + key + ".sites-cleared", s.sitesCleared());
            yml.set("players." + key + ".hasty", s.hastyHits());
        }
        if (YamlIo.trySave(yml, file)) dirty = false;
    }
}
