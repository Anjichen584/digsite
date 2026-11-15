package com.digsite.museum;

import com.digsite.achieve.AchieveStore;
import com.digsite.catalog.ArtifactCatalog;
import com.digsite.model.Model;
import com.digsite.store.MuseumStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 馆藏统计（轨道 I）：稀有度评分/分布、分馆参观汇总、全服参观排行。
 *
 * <p>数据源（跨墙只调用不修改）：
 * <ul>
 *   <li>{@link MuseumStore} 展品档案（store/**，只调用）；</li>
 *   <li>{@link AchieveStore} 通用计数 KV（achieve/**，只调用）——
 *       参观计数键约定 {@code visit:<exhibitId>}（E3 轨写入，本类只读）。</li>
 * </ul>
 */
public final class ExhibitStats {

    private ExhibitStats() {}

    /** 参观计数 KV 键前缀（与 E3 轨约定一致：visit:&lt;exhibitId&gt;）。 */
    public static final String VISIT_KEY_PREFIX = "visit:";

    /** 稀有度 → 分值：common 1 / rare 2 / epic 3 / legendary 4，未知 0。 */
    public static int rarityScore(String rarity) {
        if (rarity == null) return 0;
        return switch (rarity.toLowerCase()) {
            case "common" -> 1;
            case "rare" -> 2;
            case "epic" -> 3;
            case "legendary" -> 4;
            default -> 0;
        };
    }

    /** 馆藏指数 = Σ（每件展品的稀有度分值），每件展品算一次。 */
    public static long collectionIndex(MuseumStore store, ArtifactCatalog catalog) {
        long sum = 0;
        for (Model.ExhibitInfo ex : store.exhibits()) {
            Model.ArtifactDef def = catalog.byId(ex.artifactId());
            sum += rarityScore(def == null ? null : def.rarity());
        }
        return sum;
    }

    /** 各稀有度件数分布（键序固定 common→rare→epic→legendary，0 件也保留，便于 GUI 稳定展示）。 */
    public static Map<String, Integer> rarityDistribution(MuseumStore store, ArtifactCatalog catalog) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        dist.put("common", 0);
        dist.put("rare", 0);
        dist.put("epic", 0);
        dist.put("legendary", 0);
        for (Model.ExhibitInfo ex : store.exhibits()) {
            Model.ArtifactDef def = catalog.byId(ex.artifactId());
            if (def == null || def.rarity() == null) continue;
            dist.computeIfPresent(def.rarity().toLowerCase(), (k, v) -> v + 1);
        }
        return dist;
    }

    /** 单馆累计参观次数 = 该馆全部展品 visit 计数之和（kv 为 null 时返回 0）。 */
    public static long hallVisits(MuseumStore store, AchieveStore kv, String hallName) {
        if (kv == null) return 0;
        long sum = 0;
        for (Model.Pedestal ped : store.pedestals(hallName)) {
            if (ped.exhibitId() == null) continue;
            sum += kv.getCounter(VISIT_KEY_PREFIX + ped.exhibitId());
        }
        return sum;
    }

    /** 单件展品参观次数（kv 为 null 时返回 0）。 */
    public static long visitsOf(AchieveStore kv, String exhibitId) {
        return kv == null ? 0 : kv.getCounter(VISIT_KEY_PREFIX + exhibitId);
    }

    /** 排行条目：展品 + 累计参观次数。 */
    public record Ranked(Model.ExhibitInfo exhibit, long visits) {}

    /**
     * 全服参观排行：visit 计数降序；并列时按捐献时间先后（donatedAt 升序）稳定排序。
     * 返回全量列表，调用方自行截取 TopN。
     */
    public static List<Ranked> ranking(MuseumStore store, AchieveStore kv) {
        List<Ranked> list = new ArrayList<>();
        for (Model.ExhibitInfo ex : store.exhibits()) {
            list.add(new Ranked(ex, visitsOf(kv, ex.exhibitId())));
        }
        list.sort((a, b) -> {
            if (a.visits() != b.visits()) return Long.compare(b.visits(), a.visits());
            return Long.compare(a.exhibit().donatedAt(), b.exhibit().donatedAt());
        });
        return list;
    }
}
