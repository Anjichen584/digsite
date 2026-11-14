package com.digsite.store;

import com.digsite.model.Model;
import com.digsite.util.YamlIo;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 图鉴/纪录存档（codex-store.yml）：首修留名（只留第一个）+ 纪录墙 upsert + 全服清剿总数。 */
public final class CodexStore {
    private final File file;
    private final Map<String, Model.FirstRestoration> firstRestorations = new LinkedHashMap<>();
    private final Map<String, Model.RecordEntry> records = new LinkedHashMap<>();
    private int sitesClearedTotal = 0;
    private volatile boolean dirty = false;

    public CodexStore(File dataFolder) {
        this.file = new File(dataFolder, "codex-store.yml");
        load();
    }

    private void load() {
        YamlConfiguration yml = YamlIo.load(file);
        ConfigurationSection frSec = yml.getConfigurationSection("first-restorations");
        if (frSec != null) {
            for (String artifactId : frSec.getKeys(false)) {
                ConfigurationSection s = frSec.getConfigurationSection(artifactId);
                if (s == null) continue;
                UUID uuid = null;
                String uid = s.getString("restorer-id");
                if (uid != null && !uid.isBlank()) {
                    try { uuid = UUID.fromString(uid); } catch (IllegalArgumentException ignored) {}
                }
                firstRestorations.put(artifactId, new Model.FirstRestoration(
                        artifactId, s.getString("restorer-name", "???"), uuid, s.getLong("at", 0L)));
            }
        }
        ConfigurationSection recSec = yml.getConfigurationSection("records");
        if (recSec != null) {
            for (String key : recSec.getKeys(false)) {
                ConfigurationSection s = recSec.getConfigurationSection(key);
                if (s == null) continue;
                records.put(key, new Model.RecordEntry(
                        key, s.getString("label", key), s.getString("holder", "???"),
                        s.getString("value", ""), s.getLong("at", 0L)));
            }
        }
        sitesClearedTotal = yml.getInt("sites-cleared-total", 0);
    }

    // ---------- 首修图鉴 ----------

    /** 记录首个修复者；已有该 artifactId 记录则返回 false，不覆盖。 */
    public boolean putFirstRestoration(Model.FirstRestoration fr) {
        if (fr == null || fr.artifactId() == null || firstRestorations.containsKey(fr.artifactId())) return false;
        firstRestorations.put(fr.artifactId(), fr);
        dirty = true;
        return true;
    }

    public Model.FirstRestoration firstRestoration(String artifactId) {
        return artifactId == null ? null : firstRestorations.get(artifactId);
    }

    public Collection<Model.FirstRestoration> allFirstRestorations() {
        return List.copyOf(firstRestorations.values());
    }

    // ---------- 纪录墙 ----------

    /** upsert 一条纪录。 */
    public void putRecord(String key, String label, String holder, String value) {
        if (key == null || key.isBlank()) return;
        records.put(key, new Model.RecordEntry(key, label == null ? key : label,
                holder == null ? "???" : holder, value == null ? "" : value, System.currentTimeMillis()));
        dirty = true;
    }

    public Collection<Model.RecordEntry> records() {
        return List.copyOf(records.values());
    }

    // ---------- 全服清剿计数 ----------

    public void bumpSitesCleared() {
        sitesClearedTotal++;
        dirty = true;
    }

    public int sitesClearedTotal() {
        return sitesClearedTotal;
    }

    // ---------- 冲刷 ----------

    public void flush() {
        if (!dirty) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Model.FirstRestoration fr : firstRestorations.values()) {
            String path = "first-restorations." + fr.artifactId() + ".";
            yml.set(path + "restorer-name", fr.restorerName());
            yml.set(path + "restorer-id", fr.restorerId() == null ? null : fr.restorerId().toString());
            yml.set(path + "at", fr.at());
        }
        for (Model.RecordEntry r : records.values()) {
            String path = "records." + r.key() + ".";
            yml.set(path + "label", r.label());
            yml.set(path + "holder", r.holderName());
            yml.set(path + "value", r.value());
            yml.set(path + "at", r.at());
        }
        yml.set("sites-cleared-total", sitesClearedTotal);
        if (YamlIo.trySave(yml, file)) dirty = false;
    }
}
