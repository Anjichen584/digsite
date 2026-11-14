package com.digsite.model;

import java.util.List;

/** 全插件共享数据模型。records 不可变；持久化由 store 层负责。 */
public final class Model {
    private Model() {}

    /** 文物定义（artifacts.yml）。 */
    public record ArtifactDef(String id, String name, String rarity, int pieces,
                              String displayMaterial, List<String> lore, int weight) {}

    /** 碎片（挖掘产出，ItemCodec 编码进 ItemStack PDC）。 */
    public record FragmentData(String artifactId, int piece, int integrity, String siteId) {}

    /** 成品文物（修复产出）。repairDegree ∈ [10,100]。 */
    public record ArtifactData(String artifactId, int repairDegree, String restorer, long createdAt) {}

    /** 遗址运行时状态。cx/cy/cz 为场地几何中心的地面坐标；size 为边长（奇数）。 */
    public record SiteInfo(String id, String tier, String worldName,
                           int cx, int cy, int cz, int size, int layers,
                           long createdAt, boolean closed) {}

    /** 遗址单个挖掘格。x/z 为相对中心的偏移；layer 为当前还剩的层数（0 = 全挖完）；done 兜底。 */
    public record SiteCell(int dx, int dz, int layer, int integrity, boolean done) {}

    /** 博物馆分馆。 */
    public record HallInfo(String name, String worldName, double x, double y, double z) {}

    /** 展台。exhibitId 为 null 表示空台；entityUuid/plaqueUuid 为展示实体（可能为 null）。 */
    public record Pedestal(int index, double x, double y, double z,
                           String exhibitId, java.util.UUID entityUuid, java.util.UUID plaqueUuid) {}

    /** 博物馆展品。 */
    public record ExhibitInfo(String exhibitId, String artifactId, int repairDegree,
                              String donorName, String hallName, long donatedAt) {}

    /** 玩家统计（纯计数，非 RPG 属性）。 */
    public record PlayerStats(java.util.UUID uuid, String name,
                              int fragmentsFound, int artifactsRestored, int donations,
                              int sitesCleared, int hastyHits) {}

    /** 图鉴首修记录。 */
    public record FirstRestoration(String artifactId, String restorerName, java.util.UUID restorerId, long at) {}

    /** 全服纪录条目。 */
    public record RecordEntry(String key, String label, String holderName, String value, long at) {}
}
