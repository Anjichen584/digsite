package com.digsite.store;

import com.digsite.model.Model;
import com.digsite.util.YamlIo;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 遗址存档（sites-store.yml）：SiteInfo 列表 + 按 siteId 分组的 SiteCell；脏标记 + flush 原子写。 */
public final class SitesStore {
    private final File file;
    private final Map<String, Model.SiteInfo> sites = new LinkedHashMap<>();
    private final Map<String, List<Model.SiteCell>> cellsBySite = new LinkedHashMap<>();
    private volatile boolean dirty = false;

    public SitesStore(File dataFolder) {
        this.file = new File(dataFolder, "sites-store.yml");
        load();
    }

    private void load() {
        YamlConfiguration yml = YamlIo.load(file);
        for (Map<?, ?> row : yml.getMapList("sites")) {
            Model.SiteInfo s = siteFromMap(row);
            if (s != null && s.id() != null) sites.put(s.id(), s);
        }
        ConfigurationSection cellsSec = yml.getConfigurationSection("cells");
        if (cellsSec != null) {
            for (String siteId : cellsSec.getKeys(false)) {
                List<Model.SiteCell> list = new ArrayList<>();
                for (Map<?, ?> row : cellsSec.getMapList(siteId)) {
                    Model.SiteCell c = cellFromMap(row);
                    if (c != null) list.add(c);
                }
                cellsBySite.put(siteId, List.copyOf(list));
            }
        }
    }

    // ---------- 站点 ----------

    public void put(Model.SiteInfo site) {
        if (site == null || site.id() == null) return;
        sites.put(site.id(), site);
        cellsBySite.computeIfAbsent(site.id(), k -> List.of());
        dirty = true;
    }

    public Model.SiteInfo get(String siteId) {
        return siteId == null ? null : sites.get(siteId);
    }

    public List<Model.SiteInfo> all() {
        return List.copyOf(sites.values());
    }

    public List<Model.SiteInfo> allActive() {
        List<Model.SiteInfo> out = new ArrayList<>();
        for (Model.SiteInfo s : sites.values()) if (!s.closed()) out.add(s);
        return out;
    }

    /** 关闭遗址：仅置 closed=true，其余字段保持。 */
    public void close(String siteId) {
        Model.SiteInfo s = sites.get(siteId);
        if (s == null || s.closed()) return;
        sites.put(siteId, new Model.SiteInfo(s.id(), s.tier(), s.worldName(),
                s.cx(), s.cy(), s.cz(), s.size(), s.layers(), s.createdAt(), true));
        dirty = true;
    }

    // ---------- 挖掘格 ----------

    public void setCells(String siteId, List<Model.SiteCell> cells) {
        if (siteId == null) return;
        cellsBySite.put(siteId, cells == null ? List.of() : List.copyOf(cells));
        dirty = true;
    }

    /** 不可变视图；站点不存在返回空列表。 */
    public List<Model.SiteCell> cells(String siteId) {
        List<Model.SiteCell> list = cellsBySite.get(siteId);
        return list == null ? List.of() : list;
    }

    /** 按 index 替换单格。 */
    public void updateCell(String siteId, int index, Model.SiteCell cell) {
        List<Model.SiteCell> list = cellsBySite.get(siteId);
        if (list == null || cell == null || index < 0 || index >= list.size()) return;
        List<Model.SiteCell> copy = new ArrayList<>(list);
        copy.set(index, cell);
        cellsBySite.put(siteId, List.copyOf(copy));
        dirty = true;
    }

    /** 按 dx/dz 匹配替换单格（找不到则忽略）。 */
    public void updateCell(String siteId, Model.SiteCell cell) {
        List<Model.SiteCell> list = cellsBySite.get(siteId);
        if (list == null || cell == null) return;
        for (int i = 0; i < list.size(); i++) {
            Model.SiteCell c = list.get(i);
            if (c.dx() == cell.dx() && c.dz() == cell.dz()) {
                updateCell(siteId, i, cell);
                return;
            }
        }
    }

    // ---------- 冲刷 ----------

    public void flush() {
        if (!dirty) return;
        YamlConfiguration yml = new YamlConfiguration();
        List<Map<String, Object>> siteRows = new ArrayList<>();
        for (Model.SiteInfo s : sites.values()) siteRows.add(siteToMap(s));
        yml.set("sites", siteRows);
        for (Map.Entry<String, List<Model.SiteCell>> e : cellsBySite.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Model.SiteCell c : e.getValue()) rows.add(cellToMap(c));
            yml.set("cells." + e.getKey(), rows);
        }
        if (YamlIo.trySave(yml, file)) dirty = false;
    }

    // ---------- 序列化 ----------

    /** 通配 Map 的 getOrDefault 替身（capture 无法接收任意默认值）。 */
    private static Object gd(Map<?, ?> m, String key, Object def) {
        Object v = m.get(key);
        return v == null ? def : v;
    }

    private static Map<String, Object> siteToMap(Model.SiteInfo s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("tier", s.tier());
        m.put("world-name", s.worldName());
        m.put("cx", s.cx());
        m.put("cy", s.cy());
        m.put("cz", s.cz());
        m.put("size", s.size());
        m.put("layers", s.layers());
        m.put("created-at", s.createdAt());
        m.put("closed", s.closed());
        return m;
    }

    private static Model.SiteInfo siteFromMap(Map<?, ?> m) {
        try {
            Object id = m.get("id");
            if (id == null) return null;
            return new Model.SiteInfo(
                    String.valueOf(id),
                    String.valueOf(gd(m, "tier", "?")),
                    String.valueOf(gd(m, "world-name", "world")),
                    num(m.get("cx")), num(m.get("cy")), num(m.get("cz")),
                    num(m.get("size"), 5), num(m.get("layers"), 2),
                    longNum(m.get("created-at")), bool(m.get("closed")));
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> cellToMap(Model.SiteCell c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dx", c.dx());
        m.put("dz", c.dz());
        m.put("layer", c.layer());
        m.put("integrity", c.integrity());
        m.put("done", c.done());
        return m;
    }

    private static Model.SiteCell cellFromMap(Map<?, ?> m) {
        try {
            return new Model.SiteCell(
                    num(m.get("dx")), num(m.get("dz")),
                    num(m.get("layer")), num(m.get("integrity"), 100),
                    bool(m.get("done")));
        } catch (Exception e) {
            return null;
        }
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static long longNum(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o);
    }

    /** 供自检使用：当前站点数。 */
    public int size() {
        return Collections.unmodifiableMap(sites).size();
    }
}
