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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 博物馆存档（museum-store.yml）：分馆/展台/展品/投票记录 + 上轮投票周期键；脏标记 + flush 原子写。 */
public final class MuseumStore {
    private final File file;
    private final Map<String, Model.HallInfo> halls = new LinkedHashMap<>();
    private final Map<String, List<Model.Pedestal>> pedestalsByHall = new LinkedHashMap<>();
    private final Map<String, Model.ExhibitInfo> exhibits = new LinkedHashMap<>();
    /** periodKey → (exhibitId → 投票人 UUID 集合) */
    private final Map<String, Map<String, java.util.Set<UUID>>> votes = new LinkedHashMap<>();
    private String lastPeriodKey = "";
    private volatile boolean dirty = false;

    public MuseumStore(File dataFolder) {
        this.file = new File(dataFolder, "museum-store.yml");
        load();
    }

    private void load() {
        YamlConfiguration yml = YamlIo.load(file);
        lastPeriodKey = yml.getString("last-period-key", "");
        for (Map<?, ?> row : yml.getMapList("halls")) {
            Model.HallInfo h = hallFromMap(row);
            if (h != null) halls.put(h.name(), h);
        }
        ConfigurationSection pSec = yml.getConfigurationSection("pedestals");
        if (pSec != null) {
            for (String hallName : pSec.getKeys(false)) {
                List<Model.Pedestal> list = new ArrayList<>();
                for (Map<?, ?> row : pSec.getMapList(hallName)) {
                    Model.Pedestal p = pedestalFromMap(row);
                    if (p != null) list.add(p);
                }
                pedestalsByHall.put(hallName, List.copyOf(list));
            }
        }
        for (Map<?, ?> row : yml.getMapList("exhibits")) {
            Model.ExhibitInfo e = exhibitFromMap(row);
            if (e != null && e.exhibitId() != null) exhibits.put(e.exhibitId(), e);
        }
        ConfigurationSection vSec = yml.getConfigurationSection("votes");
        if (vSec != null) {
            for (String period : vSec.getKeys(false)) {
                ConfigurationSection per = vSec.getConfigurationSection(period);
                if (per == null) continue;
                Map<String, java.util.Set<UUID>> inner = new LinkedHashMap<>();
                for (String exId : per.getKeys(false)) {
                    java.util.Set<UUID> voters = new java.util.HashSet<>();
                    for (String u : per.getStringList(exId)) {
                        try { voters.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) {}
                    }
                    inner.put(exId, voters);
                }
                votes.put(period, inner);
            }
        }
    }

    // ---------- 分馆 ----------

    /** 新建分馆；同名（忽略大小写）返回 false 不覆盖。 */
    public boolean addHall(Model.HallInfo hall) {
        if (hall == null || hall.name() == null || hall.name().isBlank()) return false;
        for (String name : halls.keySet()) {
            if (name.equalsIgnoreCase(hall.name())) return false;
        }
        halls.put(hall.name(), hall);
        pedestalsByHall.computeIfAbsent(hall.name(), k -> List.of());
        dirty = true;
        return true;
    }

    public List<Model.HallInfo> halls() {
        return List.copyOf(halls.values());
    }

    public Model.HallInfo hallByName(String name) {
        if (name == null) return null;
        for (Map.Entry<String, Model.HallInfo> e : halls.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    // ---------- 展台 ----------

    /** 分馆展台列表；分馆不存在返回空列表。 */
    public List<Model.Pedestal> pedestals(String hallName) {
        List<Model.Pedestal> list = pedestalsByHall.get(hallName);
        return list == null ? List.of() : list;
    }

    /** 按 index 匹配替换展台；分馆不存在该 index 时忽略。 */
    public void setPedestal(String hallName, Model.Pedestal p) {
        List<Model.Pedestal> list = pedestalsByHall.get(hallName);
        if (list == null || p == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).index() == p.index()) {
                List<Model.Pedestal> copy = new ArrayList<>(list);
                copy.set(i, p);
                pedestalsByHall.put(hallName, List.copyOf(copy));
                dirty = true;
                return;
            }
        }
    }

    /** 第一个空台（exhibitId 为空即空台）。 */
    public Optional<Model.Pedestal> firstFreePedestal(String hallName) {
        for (Model.Pedestal p : pedestals(hallName)) {
            if (p.exhibitId() == null || p.exhibitId().isBlank()) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** 建馆时初始化展台组（覆盖旧组）。 */
    public void initPedestals(String hallName, List<Model.Pedestal> list) {
        if (hallName == null) return;
        pedestalsByHall.put(hallName, list == null ? List.of() : List.copyOf(list));
        dirty = true;
    }

    // ---------- 展品 ----------

    /** 添加展品；exhibitId 为空时自动生成 "EX-XXXXXXXX"，返回最终 id。 */
    public String addExhibit(Model.ExhibitInfo e) {
        String id = e == null ? null : e.exhibitId();
        if (id == null || id.isBlank()) {
            do {
                id = "EX-" + randomId8();
            } while (exhibits.containsKey(id));
        }
        exhibits.put(id, e == null
                ? new Model.ExhibitInfo(id, "?", 0, "?", "", System.currentTimeMillis())
                : new Model.ExhibitInfo(id, e.artifactId(), e.repairDegree(),
                        e.donorName(), e.hallName(), e.donatedAt())); // 生成 id 必须写回 record
        dirty = true;
        return id;
    }

    /** 通配 Map 的 getOrDefault 替身（capture 无法接收任意默认值）。 */
    private static Object gd(Map<?, ?> m, String key, Object def) {
        Object v = m.get(key);
        return v == null ? def : v;
    }

    private static String randomId8() {
        char[] alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
        StringBuilder sb = new StringBuilder(8);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) sb.append(alphabet[rnd.nextInt(alphabet.length)]);
        return sb.toString();
    }

    public Model.ExhibitInfo exhibit(String exhibitId) {
        return exhibitId == null ? null : exhibits.get(exhibitId);
    }

    public Collection<Model.ExhibitInfo> exhibits() {
        return List.copyOf(exhibits.values());
    }

    public int exhibitCount() {
        return exhibits.size();
    }

    /** 移除展品，并清掉所有引用它的展台（展台坐标保留，实体引用一并置空）。 */
    public void removeExhibit(String exhibitId) {
        if (exhibitId == null || exhibits.remove(exhibitId) == null) return;
        for (Map.Entry<String, List<Model.Pedestal>> e : pedestalsByHall.entrySet()) {
            boolean changed = false;
            List<Model.Pedestal> copy = new ArrayList<>(e.getValue());
            for (int i = 0; i < copy.size(); i++) {
                Model.Pedestal p = copy.get(i);
                if (exhibitId.equals(p.exhibitId())) {
                    copy.set(i, new Model.Pedestal(p.index(), p.x(), p.y(), p.z(), null, null, null));
                    changed = true;
                }
            }
            if (changed) pedestalsByHall.put(e.getKey(), List.copyOf(copy));
        }
        dirty = true;
    }

    // ---------- 投票 ----------

    /** 每人每轮（periodKey）一票；该轮已投过返回 false 且不记录。 */
    public boolean vote(String exhibitId, String periodKey, UUID voter) {
        if (exhibitId == null || periodKey == null || voter == null) return false;
        Map<String, java.util.Set<UUID>> period = votes.computeIfAbsent(periodKey, k -> new LinkedHashMap<>());
        java.util.Set<UUID> voters = period.get(exhibitId);
        if (voters == null) {
            voters = new java.util.HashSet<>();
            period.put(exhibitId, voters);
        }
        if (!voters.add(voter)) return false;
        dirty = true;
        return true;
    }

    /** 某轮各展品票数。 */
    public Map<String, Integer> votes(String periodKey) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Map<String, java.util.Set<UUID>> period = votes.get(periodKey);
        if (period == null) return out;
        for (Map.Entry<String, java.util.Set<UUID>> e : period.entrySet()) out.put(e.getKey(), e.getValue().size());
        return out;
    }

    public String lastPeriodKey() {
        return lastPeriodKey;
    }

    public void setLastPeriodKey(String key) {
        this.lastPeriodKey = key == null ? "" : key;
        dirty = true;
    }

    // ---------- 冲刷 ----------

    public void flush() {
        if (!dirty) return;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("last-period-key", lastPeriodKey);
        List<Map<String, Object>> hallRows = new ArrayList<>();
        for (Model.HallInfo h : halls.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", h.name());
            m.put("world-name", h.worldName());
            m.put("x", h.x());
            m.put("y", h.y());
            m.put("z", h.z());
            hallRows.add(m);
        }
        yml.set("halls", hallRows);
        for (Map.Entry<String, List<Model.Pedestal>> e : pedestalsByHall.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Model.Pedestal p : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", p.index());
                m.put("x", p.x());
                m.put("y", p.y());
                m.put("z", p.z());
                m.put("exhibit-id", p.exhibitId());
                m.put("entity-uuid", p.entityUuid() == null ? null : p.entityUuid().toString());
                m.put("plaque-uuid", p.plaqueUuid() == null ? null : p.plaqueUuid().toString());
                rows.add(m);
            }
            yml.set("pedestals." + e.getKey(), rows);
        }
        List<Map<String, Object>> exRows = new ArrayList<>();
        for (Model.ExhibitInfo e : exhibits.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exhibit-id", e.exhibitId());
            m.put("artifact-id", e.artifactId());
            m.put("repair-degree", e.repairDegree());
            m.put("donor-name", e.donorName());
            m.put("hall-name", e.hallName());
            m.put("donated-at", e.donatedAt());
            exRows.add(m);
        }
        yml.set("exhibits", exRows);
        for (Map.Entry<String, Map<String, java.util.Set<UUID>>> period : votes.entrySet()) {
            for (Map.Entry<String, java.util.Set<UUID>> e : period.getValue().entrySet()) {
                List<String> voters = new ArrayList<>();
                for (UUID u : e.getValue()) voters.add(u.toString());
                yml.set("votes." + period.getKey() + "." + e.getKey(), voters);
            }
        }
        if (YamlIo.trySave(yml, file)) dirty = false;
    }

    // ---------- 反序列化 ----------

    private static Model.HallInfo hallFromMap(Map<?, ?> m) {
        try {
            Object name = m.get("name");
            if (name == null) return null;
            return new Model.HallInfo(String.valueOf(name),
                    String.valueOf(gd(m, "world-name", "world")),
                    dbl(m.get("x")), dbl(m.get("y")), dbl(m.get("z")));
        } catch (Exception e) {
            return null;
        }
    }

    private static Model.Pedestal pedestalFromMap(Map<?, ?> m) {
        try {
            String eu = str(m.get("entity-uuid"));
            String pu = str(m.get("plaque-uuid"));
            return new Model.Pedestal(
                    intNum(m.get("index")),
                    dbl(m.get("x")), dbl(m.get("y")), dbl(m.get("z")),
                    str(m.get("exhibit-id")),
                    eu == null || eu.isBlank() ? null : UUID.fromString(eu),
                    pu == null || pu.isBlank() ? null : UUID.fromString(pu));
        } catch (Exception e) {
            return null;
        }
    }

    private static Model.ExhibitInfo exhibitFromMap(Map<?, ?> m) {
        try {
            Object id = m.get("exhibit-id");
            if (id == null) return null;
            return new Model.ExhibitInfo(String.valueOf(id),
                    String.valueOf(gd(m, "artifact-id", "?")),
                    intNum(gd(m, "repair-degree", Integer.valueOf(0))),
                    String.valueOf(gd(m, "donor-name", "?")),
                    String.valueOf(gd(m, "hall-name", "")),
                    longNum(gd(m, "donated-at", Long.valueOf(0L))));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static int intNum(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longNum(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
