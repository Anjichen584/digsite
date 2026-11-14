package com.digsite.catalog;

import com.digsite.model.Model;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 文物目录：加载 artifacts.yml；提供按遗址档位的稀有度加权 roll。 */
public final class ArtifactCatalog {
    private final Map<String, Model.ArtifactDef> defs = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> tierWeights = new LinkedHashMap<>();

    public ArtifactCatalog(File artifactsYml) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(artifactsYml);
        // jar 内默认值兜底（空盘/被删时）
        if (yml.getKeys(false).isEmpty()) {
            try (var in = getClass().getResourceAsStream("/artifacts.yml")) {
                if (in != null) yml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        for (Map<?, ?> row : yml.getMapList("artifacts")) {
            String id = String.valueOf(row.get("id"));
            if (id == null || id.isBlank()) continue;
            int pieces = toInt(row.get("pieces"), 1);
            List<String> lore = new ArrayList<>();
            Object loreObj = row.get("lore");
            if (loreObj instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
            defs.put(id, new Model.ArtifactDef(
                    id,
                    String.valueOf(gd(row, "name", id)),
                    String.valueOf(gd(row, "rarity", "common")),
                    pieces,
                    String.valueOf(gd(row, "display", "BRICK")),
                    lore,
                    toInt(row.get("weight"), 0)));
        }
        ConfigurationSection tw = yml.getConfigurationSection("site-tier-weights");
        if (tw != null) {
            for (String tier : tw.getKeys(false)) {
                ConfigurationSection t = tw.getConfigurationSection(tier);
                if (t == null) continue;
                Map<String, Integer> w = new LinkedHashMap<>();
                for (String rarity : t.getKeys(false)) w.put(rarity, t.getInt(rarity, 0));
                tierWeights.put(tier, w);
            }
        }
    }

    public List<Model.ArtifactDef> all() {
        return List.copyOf(defs.values());
    }

    public Model.ArtifactDef byId(String id) {
        return id == null ? null : defs.get(id);
    }

    public List<Model.ArtifactDef> byRarity(String rarity) {
        List<Model.ArtifactDef> out = new ArrayList<>();
        for (Model.ArtifactDef d : defs.values()) if (d.rarity().equalsIgnoreCase(rarity)) out.add(d);
        return out;
    }

    /** 按遗址档位 roll 一件文物；无匹配权重时退回任意 common。 */
    public Model.ArtifactDef roll(Random rnd, String tier) {
        Map<String, Integer> w = tierWeights.get(tier);
        if (w != null) {
            int total = 0;
            for (int v : w.values()) total += Math.max(0, v);
            if (total > 0) {
                int pick = rnd.nextInt(total);
                for (Map.Entry<String, Integer> e : w.entrySet()) {
                    pick -= Math.max(0, e.getValue());
                    if (pick < 0) {
                        List<Model.ArtifactDef> pool = byRarity(e.getKey());
                        if (!pool.isEmpty()) return pool.get(rnd.nextInt(pool.size()));
                        break;
                    }
                }
            }
        }
        List<Model.ArtifactDef> commons = byRarity("common");
        if (commons.isEmpty() && !defs.isEmpty()) return defs.values().iterator().next();
        return commons.isEmpty() ? null : commons.get(rnd.nextInt(commons.size()));
    }

    /** 通配 Map 的 getOrDefault 替身（capture 无法直接收 String/int 默认值）。 */
    private static Object gd(Map<?, ?> m, String key, Object def) {
        Object v = m.get(key);
        return v == null ? def : v;
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }
}
