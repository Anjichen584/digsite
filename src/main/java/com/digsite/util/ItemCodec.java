package com.digsite.util;

import com.digsite.model.Model;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 碎片/成品文物的 ItemStack 编解码。全部身份走 PersistentDataContainer，
 * 丢在地面/进箱子/进背包都能被识别；没有 PDC 的物品一律返回 null。
 */
public final class ItemCodec {
    private final org.bukkit.NamespacedKey kFragArtifact;
    private final org.bukkit.NamespacedKey kFragPiece;
    private final org.bukkit.NamespacedKey kFragIntegrity;
    private final org.bukkit.NamespacedKey kFragSite;
    private final org.bukkit.NamespacedKey kArtId;
    private final org.bukkit.NamespacedKey kArtDegree;
    private final org.bukkit.NamespacedKey kArtRestorer;
    private final org.bukkit.NamespacedKey kArtTime;

    public ItemCodec(Plugin plugin) {
        kFragArtifact = new org.bukkit.NamespacedKey(plugin, "frag_artifact");
        kFragPiece = new org.bukkit.NamespacedKey(plugin, "frag_piece");
        kFragIntegrity = new org.bukkit.NamespacedKey(plugin, "frag_integrity");
        kFragSite = new org.bukkit.NamespacedKey(plugin, "frag_site");
        kArtId = new org.bukkit.NamespacedKey(plugin, "art_id");
        kArtDegree = new org.bukkit.NamespacedKey(plugin, "art_degree");
        kArtRestorer = new org.bukkit.NamespacedKey(plugin, "art_restorer");
        kArtTime = new org.bukkit.NamespacedKey(plugin, "art_time");
    }

    // ---------- 碎片 ----------

    /** 碎片物品：BRICK 材质 + PDC 四键 + Lore 提示成色。integrity ∈ [1,100]。 */
    public ItemStack fragment(Model.FragmentData d, String artifactName, int totalPieces) {
        ItemStack it = new ItemStack(Material.BRICK);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.mm("<white>残片</white> <dark_gray>·</dark_gray> <gold>" + artifactName
                + "</gold> <gray>(" + (d.piece() + 1) + "/" + totalPieces + ")</gray>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>出土地：遗址 </gray><white>" + d.siteId() + "</white>"));
        lore.add(Msg.mm(qualityLine(d.integrity())));
        lore.add(Msg.mm("<dark_gray>交给博物馆修复师拼合。</dark_gray>"));
        meta.lore(lore);
        var pdc = meta.getPersistentDataContainer();
        pdc.set(kFragArtifact, org.bukkit.persistence.PersistentDataType.STRING, d.artifactId());
        pdc.set(kFragPiece, org.bukkit.persistence.PersistentDataType.INTEGER, d.piece());
        pdc.set(kFragIntegrity, org.bukkit.persistence.PersistentDataType.INTEGER, d.integrity());
        pdc.set(kFragSite, org.bukkit.persistence.PersistentDataType.STRING, d.siteId());
        it.setItemMeta(meta);
        return it;
    }

    /** 非 PDC 碎片返回 null。 */
    public Model.FragmentData readFragment(ItemStack it) {
        if (it == null || it.getType() != Material.BRICK || !it.hasItemMeta()) return null;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        String art = pdc.get(kFragArtifact, org.bukkit.persistence.PersistentDataType.STRING);
        if (art == null) return null;
        Integer piece = pdc.get(kFragPiece, org.bukkit.persistence.PersistentDataType.INTEGER);
        Integer integrity = pdc.get(kFragIntegrity, org.bukkit.persistence.PersistentDataType.INTEGER);
        String site = pdc.get(kFragSite, org.bukkit.persistence.PersistentDataType.STRING);
        if (piece == null || integrity == null) return null;
        return new Model.FragmentData(art, piece, integrity, site == null ? "?" : site);
    }

    // ---------- 成品 ----------

    public ItemStack artifact(Model.ArtifactData d, Model.ArtifactDef def) {
        Material mat = Material.matchMaterial(def.displayMaterial());
        if (mat == null) mat = Material.BRICK;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.mm("<gold>" + def.name() + "</gold>"
                + (d.repairDegree() >= 95 ? " <light_purple>✦无损</light_purple>" : "")));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String l : def.lore()) lore.add(Msg.mm(l));
        lore.add(Msg.mm(qualityLine(d.repairDegree())));
        lore.add(Msg.mm("<dark_gray>修复者：</dark_gray><white>" + d.restorer() + "</white>"
                + " <dark_gray>" + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(d.createdAt())) + "</dark_gray>"));
        meta.lore(lore);
        var pdc = meta.getPersistentDataContainer();
        pdc.set(kArtId, org.bukkit.persistence.PersistentDataType.STRING, d.artifactId());
        pdc.set(kArtDegree, org.bukkit.persistence.PersistentDataType.INTEGER, d.repairDegree());
        pdc.set(kArtRestorer, org.bukkit.persistence.PersistentDataType.STRING, d.restorer());
        pdc.set(kArtTime, org.bukkit.persistence.PersistentDataType.LONG, d.createdAt());
        it.setItemMeta(meta);
        return it;
    }

    /** 非 PDC 成品返回 null。 */
    public Model.ArtifactData readArtifact(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(kArtId, org.bukkit.persistence.PersistentDataType.STRING);
        if (id == null) return null;
        Integer degree = pdc.get(kArtDegree, org.bukkit.persistence.PersistentDataType.INTEGER);
        String restorer = pdc.get(kArtRestorer, org.bukkit.persistence.PersistentDataType.STRING);
        Long time = pdc.get(kArtTime, org.bukkit.persistence.PersistentDataType.LONG);
        if (degree == null || restorer == null) return null;
        return new Model.ArtifactData(id, degree, restorer, time == null ? 0L : time);
    }

    // ---------- 成色文案 ----------

    public static String qualityLine(int degree) {
        if (degree >= 95) return "<light_purple>成色：无损</light_purple> <white>(" + degree + ")</white>";
        if (degree >= 60) return "<yellow>成色：完整</yellow> <gray>(" + degree + ")</gray>";
        return "<gray>成色：残缺</gray> <dark_gray>(" + degree + ")</dark_gray>";
    }

    /** 给自检用：四键可见性检查。 */
    public String keyNames() {
        return kFragArtifact.getKey() + "," + kArtId.getKey();
    }
}
