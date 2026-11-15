package com.digsite.museum;

import com.digsite.util.Msg;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 迷你复原件（轨道 I2）：SPYGLASS 造型（1.21 真实物品，区别于拓片的 PAPER），
 * PDC 键 {@code digsite:mini_replica} = artifactId，纯装饰、不可捐献、不计入馆藏统计。
 *
 * <p>与 KitCodec 的拓片键（{@code digsite:replica}）同命名空间、互不冲突；
 * 判定方法放在 museum 包内（{@link #is(ItemStack)}），不改动 util/KitCodec。
 */
public final class MiniReplica {

    /** PDC 身份键（命名空间 digsite，与 KitCodec 约定一致）。 */
    private static final NamespacedKey MINI = new NamespacedKey("digsite", "mini_replica");

    private MiniReplica() {}

    /** 订做价：绿宝石数量。 */
    public static final int COST_EMERALD = 16;
    /** 订做价：铁锭数量。 */
    public static final int COST_IRON = 8;
    /** 订做价：文物对应 display 材质件数。 */
    public static final int COST_DISPLAY = 4;

    /** 生成迷你复原件：SPYGLASS + 展示名 + lore + PDC 标记。 */
    public static ItemStack create(String artifactId, String artifactName) {
        ItemStack it = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.mm("<gold>" + (artifactName == null ? artifactId : artifactName)
                + "</gold> <gray>· 复原件</gray>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>馆藏监制 · 仅作装饰 · 不可捐献</gray>"));
        lore.add(Msg.mm("<dark_gray>可放入物品展示框装点你的基地</dark_gray>"));
        lore.add(Msg.mm("<dark_gray>文物编号：</dark_gray><dark_green>" + artifactId + "</dark_green>"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(MINI, PersistentDataType.STRING, artifactId);
        it.setItemMeta(meta);
        return it;
    }

    /** PDC 中的 artifactId；非迷你复原件返回 null。 */
    public static String id(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(MINI, PersistentDataType.STRING);
    }

    /** 是否为迷你复原件（donateHand 拦截与自检用）。 */
    public static boolean is(ItemStack it) {
        return id(it) != null;
    }
}
