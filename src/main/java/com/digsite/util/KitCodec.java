package com.digsite.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 修复套件与纪念拓片的 ItemStack 编解码（E1/E3）。
 *
 * <p>全部身份走 PersistentDataContainer（命名空间 digsite，与 ItemCodec 的插件命名空间一致）：
 * <ul>
 *   <li>{@code digsite:kit_type} = tweezer | glue —— 修复套件；</li>
 *   <li>{@code digsite:replica} = artifactId —— 馆藏纪念拓片（不可捐献）。</li>
 * </ul>
 *
 * <p>键用固定字符串命名空间构造（{@code new NamespacedKey("digsite", …)}），与
 * {@code NamespacedKey(插件, …)} 产生的键完全一致（插件名 DigSite 小写即 digsite），
 * 因此 {@link #isReplica(ItemStack)} 可作纯静态方法供自检直接调用。
 */
public final class KitCodec {

    /** 精修钳（修复度 +10 加成，修复成功消耗 1 把）。 */
    public static final String KIT_TWEZER = "tweezer";
    /** 粘合剂（每片成色保底抬升，修复成功消耗 1 瓶）。 */
    public static final String KIT_GLUE = "glue";

    private static final NamespacedKey KIT_TYPE = new NamespacedKey("digsite", "kit_type");
    private static final NamespacedKey REPLICA = new NamespacedKey("digsite", "replica");

    /** 拓片兑换价：绿宝石数量。 */
    public static final int REPLICA_COST = 8;

    private KitCodec() {}

    // ---------- 套件物品 ----------

    /** 精修钳：铁剪造型 + PDC 标记 + 用途说明 Lore。 */
    public static ItemStack tweezer() {
        return kitItem(Material.SHEARS, KIT_TWEZER, "<gold>精修钳</gold>",
                List.of("<gray>手持或背包含有时参与修复：</gray>",
                        "<yellow>· 成品修复度 +10</yellow>（上限 100）",
                        "<dark_red>· 每次修复成功消耗 1 把</dark_red>"));
    }

    /** 粘合剂：蜂蜜瓶造型 + PDC 标记 + 用途说明 Lore。 */
    public static ItemStack glue() {
        return kitItem(Material.HONEY_BOTTLE, KIT_GLUE, "<gold>粘合剂</gold>",
                List.of("<gray>背包含有时参与修复：</gray>",
                        "<yellow>· 每片残片成色保底抬到 60</yellow><gray> 再求均值</gray>",
                        "<dark_red>· 每次修复成功消耗 1 瓶</dark_red>"));
    }

    private static ItemStack kitItem(Material mat, String type, String nameMini, List<String> loreMinis) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.mm(nameMini));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String l : loreMinis) lore.add(Msg.mm(l));
        lore.add(Msg.mm("<dark_gray>修复工具 · 可原版合成</dark_gray>"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(KIT_TYPE, org.bukkit.persistence.PersistentDataType.STRING, type);
        it.setItemMeta(meta);
        return it;
    }

    /** 读取物品的套件类型：tweezer / glue / null（非套件）。 */
    public static String kitType(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String v = it.getItemMeta().getPersistentDataContainer()
                .get(KIT_TYPE, org.bukkit.persistence.PersistentDataType.STRING);
        return (KIT_TWEZER.equals(v) || KIT_GLUE.equals(v)) ? v : null;
    }

    /** 背包（含主手）是否持有精修钳。 */
    public static boolean hasTweezer(Player p) {
        return findKit(p, KIT_TWEZER) != -1;
    }

    /** 背包（含主手）是否持有粘合剂。 */
    public static boolean hasGlue(Player p) {
        return findKit(p, KIT_GLUE) != -1;
    }

    /** 消耗 1 把精修钳；没有返回 false。 */
    public static boolean consumeTweezer(Player p) {
        return consumeKit(p, KIT_TWEZER);
    }

    /** 消耗 1 瓶粘合剂；没有返回 false。 */
    public static boolean consumeGlue(Player p) {
        return consumeKit(p, KIT_GLUE);
    }

    private static int findKit(Player p, String type) {
        ItemStack[] storage = p.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            ItemStack it = storage[i];
            if (it == null || it.getAmount() <= 0) continue;
            if (type.equals(kitType(it))) return i;
        }
        return -1;
    }

    private static boolean consumeKit(Player p, String type) {
        int slot = findKit(p, type);
        if (slot == -1) return false;
        ItemStack it = p.getInventory().getStorageContents()[slot];
        if (it == null) return false;
        if (it.getAmount() <= 1) {
            p.getInventory().setItem(slot, null);
        } else {
            ItemStack one = it.clone();
            one.setAmount(it.getAmount() - 1);
            p.getInventory().setItem(slot, one);
        }
        return true;
    }

    /**
     * 纯函数（供自检）：对物品数组做「按套件类型精确移除 1 件」，返回移除数量（0 或 1）。
     * 直接改动传入数组内的 ItemStack（减 amount 或置 null）；玩家背包路径由 consumeKit 包装。
     */
    public static int consumeFrom(ItemStack[] storage, String type) {
        if (storage == null) return 0;
        for (int i = 0; i < storage.length; i++) {
            ItemStack it = storage[i];
            if (it == null || it.getAmount() <= 0) continue;
            if (type.equals(kitType(it))) {
                if (it.getAmount() <= 1) {
                    storage[i] = null;
                } else {
                    it.setAmount(it.getAmount() - 1);
                }
                return 1;
            }
        }
        return 0;
    }

    // ---------- 配方 ----------

    /**
     * 注册两个 ShapedRecipe（config restore.kits-enabled=false 时不调用本方法）：
     * <ul>
     *   <li>精修钳：一行 铁锭-铜锭-铁锭（铁×2 + 铜×1）；</li>
     *   <li>粘合剂：一行 粘液球-纸。</li>
     * </ul>
     */
    public static void registerRecipes(Plugin plugin) {
        ShapedRecipe tweezer = new ShapedRecipe(new NamespacedKey(plugin, "tweezer"), tweezer());
        tweezer.shape("ICI");
        tweezer.setIngredient('I', Material.IRON_INGOT);
        tweezer.setIngredient('C', Material.COPPER_INGOT);
        plugin.getServer().addRecipe(tweezer);

        ShapedRecipe glue = new ShapedRecipe(new NamespacedKey(plugin, "glue"), glue());
        glue.shape("SP");
        glue.setIngredient('S', Material.SLIME_BALL);
        glue.setIngredient('P', Material.PAPER);
        plugin.getServer().addRecipe(glue);
    }

    // ---------- 纪念拓片 ----------

    /** 拓片纪念品：纸 + 「&lt;文物名&gt; · 拓片」+ PDC replica=artifactId + 不可捐献声明。 */
    public static ItemStack replica(String artifactId, String artifactName) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.mm("<gold>" + (artifactName == null ? artifactId : artifactName)
                + "</gold> <gray>· 拓片</gray>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>馆藏纪念，不可捐献。</gray>"));
        lore.add(Msg.mm("<dark_gray>文物编号：</dark_gray><dark_green>" + artifactId + "</dark_green>"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(REPLICA, org.bukkit.persistence.PersistentDataType.STRING, artifactId);
        it.setItemMeta(meta);
        return it;
    }

    /** 是否为纪念拓片（PDC replica 键存在即认定，静态方法供捐献校验与自检）。 */
    public static boolean isReplica(ItemStack it) {
        return replicaId(it) != null;
    }

    /** 拓片对应的 artifactId；非拓片返回 null。 */
    public static String replicaId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer()
                .get(REPLICA, org.bukkit.persistence.PersistentDataType.STRING);
    }
}
