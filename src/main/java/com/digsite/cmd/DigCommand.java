package com.digsite.cmd;

import com.digsite.DigSitePlugin;
import com.digsite.model.Model;
import com.digsite.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /digs 主命令：分派 + Tab 补全；文案与 SUBCOMMANDS/ADMIN_SUBS/helpLines 三处同源。 */
public final class DigCommand implements CommandExecutor, TabCompleter {

    public static final List<String> SUBCOMMANDS = List.of(
            "help", "board", "codex", "museum", "restore", "donate", "vote", "achieve", "stats", "selftest", "admin");

    public static final List<String> ADMIN_SUBS = List.of(
            "spawn", "tp", "close", "hall", "exhibit", "reload");

    private static final String NOT_PLAYER = "<red>该指令只能由玩家执行。</red>";

    private final DigSitePlugin plugin;
    private final SelfTest selfTest;

    public DigCommand(DigSitePlugin plugin, SelfTest selfTest) {
        this.plugin = plugin;
        this.selfTest = selfTest;
    }

    // ---------- 帮助（玩家段 + 管理员段两段式） ----------

    /** 逐条覆盖 SUBCOMMANDS 与 ADMIN_SUBS；自检对齐校验以此文本为准。 */
    public static List<String> helpLines() {
        return List.of(
                "<gold>—— 玩家指令 ——</gold>",
                "<white>/digs help</white> <gray>— 查看本帮助</gray>",
                "<white>/digs board</white> <gray>— 查看当前活跃遗址列表</gray>",
                "<white>/digs codex [页码]</white> <gray>— 打开全服文物图鉴</gray>",
                "<white>/digs museum</white> <gray>— 打开博物馆总览</gray>",
                "<white>/digs restore</white> <gray>— 打开碎片修复台（集齐同一文物的碎片可拼合）</gray>",
                "<white>/digs donate</white> <gray>— 手持成品文物捐入博物馆</gray>",
                "<white>/digs vote 展品ID</white> <gray>— 为镇馆之宝投票（每人每轮一票）</gray>",
                "<white>/digs achieve</white> <gray>— 打开考古成就墙（12 条成就与进度）</gray>",
                "<white>/digs stats [玩家名]</white> <gray>— 查看挖掘/捐献统计与纪录墙</gray>",
                "<white>/digs selftest</white> <gray>— 运行插件自检</gray>",
                "<white>/digs admin …</white> <gray>— 管理员指令，见下</gray>",
                "<gold>—— 管理员指令（需要 digs.admin）——</gold>",
                "<white>/digs admin spawn tier</white> <gray>— 按档位随机落一处遗址</gray>",
                "<white>/digs admin tp siteId</white> <gray>— 传送到指定遗址中心上空</gray>",
                "<white>/digs admin close siteId</white> <gray>— 管理员关闭遗址</gray>",
                "<white>/digs admin hall add 名称</white> <gray>— 以脚下位置新建分馆</gray>",
                "<white>/digs admin hall list</white> <gray>— 列出分馆与空台数</gray>",
                "<white>/digs admin exhibit remove 展品ID</white> <gray>— 拆除展品</gray>",
                "<white>/digs admin reload</white> <gray>— 重载 config.yml</gray>");
    }

    // ---------- 分派 ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> {
                for (String line : helpLines()) Msg.send(sender, line);
            }
            case "board" -> handleBoard(sender);
            case "codex" -> {
                Player p = requirePlayer(sender);
                if (p == null) return true;
                int page = 1;
                if (args.length >= 2) {
                    try {
                        page = Math.max(1, Integer.parseInt(args[1]));
                    } catch (NumberFormatException e) {
                        Msg.send(sender, "<red>页码必须是数字：<white>" + args[1] + "</white></red>");
                        return true;
                    }
                }
                plugin.codexService().openCodex(p, page);
            }
            case "museum" -> {
                Player p = requirePlayer(sender);
                if (p == null) return true;
                plugin.museumService().openOverview(p);
            }
            case "restore" -> {
                Player p = requirePlayer(sender);
                if (p == null) return true;
                plugin.restorationService().open(p);
            }
            case "donate" -> {
                Player p = requirePlayer(sender);
                if (p == null) return true;
                plugin.museumService().donateHand(p);
            }
            case "vote" -> {
                Player p = requirePlayer(sender);
                if (p == null) return true;
                if (args.length < 2) {
                    Msg.send(sender, "<red>用法：<white>/digs vote 展品ID</white>（/digs museum 里可查）</red>");
                    return true;
                }
                plugin.voteService().vote(p, args[1]);
            }
            case "achieve" -> {
                Player p = requirePlayer(sender);
                if (p == null) return true;
                plugin.achieveService().openGui(p);
            }
            case "stats" -> handleStats(sender, args);
            case "selftest" -> {
                int failed = selfTest.run(sender);
                Msg.send(sender, failed == 0
                        ? "<green>自检完成，全部通过。</green>"
                        : "<red>自检完成，失败 " + failed + " 项，详见上方输出。</red>");
            }
            case "admin" -> handleAdmin(sender, args);
            default -> {
                Msg.send(sender, "<red>未知子指令 <white>" + sub + "</white>，用 /digs help 查看帮助。</red>");
            }
        }
        return true;
    }

    // ---------- 各子指令 ----------

    private void handleBoard(CommandSender sender) {
        List<Model.SiteInfo> active = plugin.siteService().activeSites();
        if (active.isEmpty()) {
            Msg.send(sender, "<gray>当前没有活跃遗址，等下一处落下再出发吧。</gray>");
            return;
        }
        Msg.send(sender, "<gold>活跃遗址 " + active.size() + " 处：</gold>");
        for (Model.SiteInfo s : active) {
            Msg.send(sender, "<white>" + s.id() + "</white> <gray>·</gray> <gold>" + s.tier()
                    + "</gold> <gray>·</gray> " + s.worldName()
                    + " <gray>(" + s.cx() + "," + s.cz() + ")</gray>"
                    + " <dark_gray>规模 " + s.size() + "x" + s.size() + " / " + s.layers() + " 层</dark_gray>");
        }
    }

    private void handleStats(CommandSender sender, String[] args) {
        UUID uuid;
        String name;
        if (args.length >= 2) {
            Player online = Bukkit.getPlayerExact(args[1]);
            if (online != null) {
                uuid = online.getUniqueId();
                name = online.getName();
            } else {
                var off = Bukkit.getOfflinePlayer(args[1]);
                if (!off.hasPlayedBefore() && !(sender instanceof Player)) {
                    Msg.send(sender, "<red>找不到玩家 <white>" + args[1] + "</white>。</red>");
                    return;
                }
                uuid = off.getUniqueId();
                name = args[1];
            }
        } else if (sender instanceof Player p) {
            uuid = p.getUniqueId();
            name = p.getName();
        } else {
            Msg.send(sender, "<red>控制台请用 <white>/digs stats 玩家名</white>。</red>");
            return;
        }
        Model.PlayerStats s = plugin.playerStore().stats(uuid, name);
        Msg.send(sender, "<gold>" + s.name() + " 的考古档案：</gold>"
                + " 碎片 " + s.fragmentsFound()
                + " <gray>·</gray> 修复 " + s.artifactsRestored()
                + " <gray>·</gray> 捐献 " + s.donations()
                + " <gray>·</gray> 清剿遗址 " + s.sitesCleared()
                + " <gray>·</gray> 手速惩罚 " + s.hastyHits());
        List<String> records = plugin.codexService().recordLines();
        if (records != null && !records.isEmpty()) {
            Msg.send(sender, "<gold>—— 全服纪录 ——</gold>");
            for (String line : records) sender.sendMessage(Msg.prefixed(line));
        }
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        // handler 内自判权限（Tab 隐藏不算）；控制台视为有权限
        if (sender instanceof Player && !sender.hasPermission("digs.admin")) {
            Msg.send(sender, "<red>需要管理员权限 digs.admin。</red>");
            return;
        }
        if (args.length < 2) {
            Msg.send(sender, "<red>用法：/digs admin <" + String.join("|", ADMIN_SUBS) + "></red>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (args.length < 3) {
                    Msg.send(sender, "<red>用法：/digs admin spawn <tier>，可用档位："
                            + plugin.siteService().tierNames() + "</red>");
                    return;
                }
                String tier = args[2].toLowerCase(Locale.ROOT);
                if (!plugin.siteService().tierNames().contains(tier)) {
                    Msg.send(sender, "<red>档位不存在 <white>" + tier + "</white>，可用档位："
                            + plugin.siteService().tierNames() + "</red>");
                    return;
                }
                Model.SiteInfo site = plugin.siteService().spawnRandom(tier);
                if (site == null) {
                    Msg.send(sender, "<red>落点失败：附近没有满足间距要求的位置，稍后再试。</red>");
                } else {
                    Msg.send(sender, "<green>遗址已落下：</green><white>" + site.id() + "</white>"
                            + " <gray>" + site.worldName() + " (" + site.cx() + "," + site.cz() + ")</gray>");
                }
            }
            case "tp" -> {
                Player p = requirePlayer(sender);
                if (p == null) return;
                if (args.length < 3) {
                    Msg.send(sender, "<red>用法：/digs admin tp <siteId></red>");
                    return;
                }
                Model.SiteInfo site = plugin.sitesStore().get(args[2]);
                if (site == null) {
                    Msg.send(sender, "<red>遗址不存在：<white>" + args[2] + "</white>（/digs board 查看）</red>");
                    return;
                }
                var world = Bukkit.getWorld(site.worldName());
                if (world == null) {
                    Msg.send(sender, "<red>世界未加载：" + site.worldName() + "</red>");
                    return;
                }
                Location top = new Location(world, site.cx() + 0.5,
                        world.getHighestBlockYAt(site.cx(), site.cz()) + 1.0, site.cz() + 0.5);
                p.teleport(top);
                Msg.send(sender, "<green>已传送到遗址 </green><white>" + site.id() + "</white><green> 上空。</green>");
            }
            case "close" -> {
                if (args.length < 3) {
                    Msg.send(sender, "<red>用法：/digs admin close <siteId></red>");
                    return;
                }
                if (plugin.sitesStore().get(args[2]) == null) {
                    Msg.send(sender, "<red>遗址不存在：<white>" + args[2] + "</white></red>");
                    return;
                }
                plugin.siteService().closeSite(args[2], "管理员关闭");
                Msg.send(sender, "<green>已关闭遗址 " + args[2] + "。</green>");
            }
            case "hall" -> handleAdminHall(sender, args);
            case "exhibit" -> {
                if (args.length >= 3 && args[2].equalsIgnoreCase("remove")) {
                    if (args.length < 4) {
                        Msg.send(sender, "<red>用法：/digs admin exhibit remove <展品ID></red>");
                        return;
                    }
                    plugin.museumService().removeExhibit(args[3], true);
                    Msg.send(sender, "<green>已移除展品 " + args[3] + "（纪录与捐献榜保留）。</green>");
                } else {
                    Msg.send(sender, "<red>用法：/digs admin exhibit remove <展品ID></red>");
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                Msg.send(sender, "<green>config.yml 已重载。</green>");
            }
            default -> Msg.send(sender, "<red>未知管理子指令 <white>" + args[1] + "</white>。</red>");
        }
    }

    private void handleAdminHall(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[2].equalsIgnoreCase("add")) {
            Player p = requirePlayer(sender);
            if (p == null) return;
            if (args.length < 4) {
                Msg.send(sender, "<red>用法：/digs admin hall add <名称></red>");
                return;
            }
            String name = args[3];
            plugin.museumService().addHall(name, p.getLocation());
            // 成败由服务侧回执；这里只提示已受理（同名会被服务拒绝并另行提示）
            Msg.send(sender, "<gray>分馆申请已提交：</gray><white>" + name + "</white><gray>，以你的站位为圆心。</gray>");
            return;
        }
        if (args.length >= 3 && args[2].equalsIgnoreCase("list")) {
            var halls = plugin.museumStore().halls();
            if (halls.isEmpty()) {
                Msg.send(sender, "<gray>还没有分馆，用 /digs admin hall add <名称> 创建。</gray>");
                return;
            }
            Msg.send(sender, "<gold>分馆 " + halls.size() + " 座：</gold>");
            for (Model.HallInfo h : halls) {
                List<Model.Pedestal> ps = plugin.museumStore().pedestals(h.name());
                long free = ps.stream().filter(x -> x.exhibitId() == null || x.exhibitId().isBlank()).count();
                Msg.send(sender, "<white>" + h.name() + "</white> <gray>·</gray> 展台 " + ps.size()
                        + " <gray>·</gray> 空台 " + free
                        + " <dark_gray>" + h.worldName() + " (" + (int) h.x() + "," + (int) h.y() + "," + (int) h.z() + ")</dark_gray>");
            }
            return;
        }
        Msg.send(sender, "<red>用法：/digs admin hall add <名称> | hall list</red>");
    }

    // ---------- Tab 补全 ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean adminPerm = !(sender instanceof Player) || sender.hasPermission("digs.admin");
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : SUBCOMMANDS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))
                        && (!s.equals("admin") || adminPerm)) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && adminPerm) {
            List<String> out = new ArrayList<>();
            for (String s : ADMIN_SUBS) if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && adminPerm) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "spawn" -> {
                    List<String> out = new ArrayList<>();
                    for (String t : plugin.siteService().tierNames()) {
                        if (t.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(t);
                    }
                    return out;
                }
                case "tp", "close" -> {
                    List<String> out = new ArrayList<>();
                    for (Model.SiteInfo s : plugin.sitesStore().all()) {
                        if (s.id().startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s.id());
                    }
                    return out;
                }
                case "hall" -> {
                    return filter(List.of("add", "list"), args[2]);
                }
                case "exhibit" -> {
                    return filter(List.of("remove"), args[2]);
                }
                default -> { }
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && adminPerm
                && args[1].equalsIgnoreCase("hall") && args[2].equalsIgnoreCase("add")) {
            return List.of();
        }
        if (args[0].equalsIgnoreCase("vote") && args.length == 2) {
            List<String> ids = new ArrayList<>();
            for (Model.ExhibitInfo e : plugin.museumStore().exhibits()) {
                if (e.exhibitId().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) ids.add(e.exhibitId());
            }
            return ids;
        }
        if (args[0].equalsIgnoreCase("stats") && args.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) names.add(p.getName());
            }
            return names;
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String s : options) if (s.startsWith(p)) out.add(s);
        return out;
    }

    // ---------- 工具 ----------

    /** 玩家专用指令的友好拦截；非玩家返回 null 并已提示。 */
    private static Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player p) return p;
        Msg.send(sender, NOT_PLAYER);
        return null;
    }
}
