package com.digsite.cmd;

import com.digsite.DigSitePlugin;
import com.digsite.achieve.AchieveStore;
import com.digsite.model.Model;
import com.digsite.restore.RestorationService;
import com.digsite.store.CodexStore;
import com.digsite.store.MuseumStore;
import com.digsite.store.PlayerStore;
import com.digsite.store.SitesStore;
import com.digsite.util.ItemCodec;
import com.digsite.util.KitCodec;
import com.digsite.util.Msg;
import com.digsite.util.YamlIo;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/** 插件自检：codec/目录/YamlIo/配置/命令同源/模型数学/存档往返 全量断言；sender 为 null 时只写日志。 */
public final class SelfTest {
    private final DigSitePlugin plugin;
    private int total = 0;
    private int failed = 0;
    private final List<String> failures = new ArrayList<>();

    public SelfTest(DigSitePlugin plugin) {
        this.plugin = plugin;
    }

    public int lastTotal() {
        return total;
    }

    public int lastFailed() {
        return failed;
    }

    // ---------- 周期键（供 VoteService 逻辑参考） ----------

    /**
     * 投票周期键：epochDay 对 periodDays 整除分桶，形如 "P0"、"P1"。
     * periodDays ≤ 1 时按 1 处理（每天一轮，永不抛异常）。
     */
    public static String periodKeyOf(long epochDay, long periodDays) {
        long p = Math.max(1L, periodDays);
        return "P" + Math.floorDiv(epochDay, p);
    }

    // ---------- 断言框架 ----------

    private interface Check {
        void run() throws Exception;
    }

    private void t(String name, Check c) {
        total++;
        try {
            c.run();
        } catch (Throwable ex) {
            failed++;
            failures.add(name + " → " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static void a(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void eq(Object expect, Object actual, String msg) {
        if (!Objects.equals(expect, actual)) {
            throw new AssertionError(msg + " 期望=" + expect + " 实际=" + actual);
        }
    }

    private static void near(long expect, long actual, long delta, String msg) {
        if (Math.abs(expect - actual) > delta) {
            throw new AssertionError(msg + " 期望≈" + expect + " 实际=" + actual);
        }
    }

    // ---------- 主流程 ----------

    public int run(CommandSender sender) {
        total = 0;
        failed = 0;
        failures.clear();
        runCodec();
        runKits();
        runRestoreMath();
        runCatalog();
        runYamlIo();
        runConfig();
        runCommandSources();
        runModelMath();
        runStores();
        String head = "自检 " + (total - failed) + "/" + total + " 成功，失败 " + failed;
        output(sender, failed == 0 ? "<green>" + head + "</green>" : "<red>" + head + "</red>");
        for (String f : failures) output(sender, "<dark_red>[失败]</dark_red> " + f);
        return failed;
    }

    private void output(CommandSender sender, String mini) {
        if (sender == null) {
            plugin.getLogger().info(Msg.plain(mini));
        } else {
            Msg.send(sender, mini);
        }
    }

    // ---------- 1. ItemCodec ----------

    private void runCodec() {
        ItemCodec codec = plugin.codec();
        t("碎片往返 artifactId", () -> {
            Model.FragmentData d = new Model.FragmentData("bone_flute", 2, 77, "S1");
            eq(d.artifactId(), codec.readFragment(codec.fragment(d, "骨笛", 4)).artifactId(), "artifactId");
        });
        t("碎片往返 piece", () -> {
            Model.FragmentData d = new Model.FragmentData("bone_flute", 2, 77, "S1");
            eq(d.piece(), codec.readFragment(codec.fragment(d, "骨笛", 4)).piece(), "piece");
        });
        t("碎片往返 integrity", () -> {
            Model.FragmentData d = new Model.FragmentData("bone_flute", 2, 77, "S1");
            eq(d.integrity(), codec.readFragment(codec.fragment(d, "骨笛", 4)).integrity(), "integrity");
        });
        t("碎片往返 siteId", () -> {
            Model.FragmentData d = new Model.FragmentData("bone_flute", 2, 77, "S1");
            eq(d.siteId(), codec.readFragment(codec.fragment(d, "骨笛", 4)).siteId(), "siteId");
        });
        t("碎片材质为 BRICK", () ->
                a(codec.fragment(new Model.FragmentData("x", 0, 50, "s"), "骨笛", 4).getType() == Material.BRICK, "应为 BRICK"));
        t("成品往返 artifactId", () -> {
            Model.ArtifactDef def = plugin.catalog().all().get(0);
            Model.ArtifactData d = new Model.ArtifactData(def.id(), 88, "测试者", 1700000000000L);
            eq(d.artifactId(), codec.readArtifact(codec.artifact(d, def)).artifactId(), "artifactId");
        });
        t("成品往返 repairDegree", () -> {
            Model.ArtifactDef def = plugin.catalog().all().get(0);
            Model.ArtifactData d = new Model.ArtifactData(def.id(), 88, "测试者", 1700000000000L);
            eq(d.repairDegree(), codec.readArtifact(codec.artifact(d, def)).repairDegree(), "repairDegree");
        });
        t("成品往返 restorer", () -> {
            Model.ArtifactDef def = plugin.catalog().all().get(0);
            Model.ArtifactData d = new Model.ArtifactData(def.id(), 88, "测试者", 1700000000000L);
            eq(d.restorer(), codec.readArtifact(codec.artifact(d, def)).restorer(), "restorer");
        });
        t("成品往返 createdAt", () -> {
            Model.ArtifactDef def = plugin.catalog().all().get(0);
            Model.ArtifactData d = new Model.ArtifactData(def.id(), 88, "测试者", 1700000000000L);
            eq(d.createdAt(), codec.readArtifact(codec.artifact(d, def)).createdAt(), "createdAt");
        });
        t("无 PDC 物品 readFragment 为 null", () ->
                a(codec.readFragment(new ItemStack(Material.STONE)) == null, "普通石头不应被识别为碎片"));
        // 成色三档边界（含 10 / 100）
        t("成色 10 → 残缺", () -> a(ItemCodec.qualityLine(10).contains("残缺"), "10 应为残缺"));
        t("成色 59 → 残缺", () -> a(ItemCodec.qualityLine(59).contains("残缺"), "59 应为残缺"));
        t("成色 60 → 完整", () -> a(ItemCodec.qualityLine(60).contains("完整"), "60 应为完整"));
        t("成色 94 → 完整", () -> a(ItemCodec.qualityLine(94).contains("完整"), "94 应为完整"));
        t("成色 95 → 无损", () -> a(ItemCodec.qualityLine(95).contains("无损"), "95 应为无损"));
        t("成色 100 → 无损", () -> a(ItemCodec.qualityLine(100).contains("无损"), "100 应为无损"));
    }

    // ---------- 1.5 KitCodec（E1 套件 / E3 拓片） ----------

    private void runKits() {
        t("套件精修钳 kit_type=tweezer", () ->
                a("tweezer".equals(KitCodec.kitType(KitCodec.tweezer())), "kitType 应为 tweezer"));
        t("套件粘合剂 kit_type=glue", () ->
                a("glue".equals(KitCodec.kitType(KitCodec.glue())), "kitType 应为 glue"));
        t("普通物品 kitType 为 null", () ->
                a(KitCodec.kitType(new ItemStack(Material.STONE)) == null, "石头不应被识别为套件"));
        t("拓片 isReplica 识别", () ->
                a(KitCodec.isReplica(KitCodec.replica("bone_flute", "骨笛")), "拓片应为 replica"));
        t("普通纸不是拓片", () ->
                a(!KitCodec.isReplica(new ItemStack(Material.PAPER)), "普通纸不应为 replica"));
        t("拓片 artifactId 往返", () ->
                eq("bone_flute", KitCodec.replicaId(KitCodec.replica("bone_flute", "骨笛")), "replicaId"));
        t("拓片材质为 PAPER", () ->
                a(KitCodec.replica("x", "某文物").getType() == Material.PAPER, "拓片应为 PAPER"));
        t("consumeFrom 精确移除 1 个（3→2）", () -> {
            ItemStack[] storage = {KitCodec.tweezer()};
            storage[0].setAmount(3);
            eq(1, KitCodec.consumeFrom(storage, "tweezer"), "移除数");
            eq(2, storage[0].getAmount(), "剩余数量");
        });
        t("consumeFrom 单件移除置空", () -> {
            ItemStack[] storage = {KitCodec.glue()};
            eq(1, KitCodec.consumeFrom(storage, "glue"), "移除数");
            a(storage[0] == null, "单件应置 null");
        });
        t("consumeFrom 非套件不动", () -> {
            ItemStack[] storage = {new ItemStack(Material.SLIME_BALL, 5)};
            eq(0, KitCodec.consumeFrom(storage, "glue"), "不应移除");
            eq(5, storage[0].getAmount(), "数量不变");
        });
        t("consumeFrom 空数组安全", () ->
                eq(0, KitCodec.consumeFrom(new ItemStack[0], "tweezer"), "空数组返回 0"));
        t("两配方已注册", () -> {
            a(!plugin.getServer().getRecipesFor(KitCodec.tweezer()).isEmpty(), "精修钳配方缺失");
            a(!plugin.getServer().getRecipesFor(KitCodec.glue()).isEmpty(), "粘合剂配方缺失");
        });
    }

    // ---------- 1.7 修复度合成（E1 口径纯函数） ----------

    private void runRestoreMath() {
        t("合成 无套件 88 → 88", () ->
                eq(88, RestorationService.composeDegree(new int[]{88}, 0, 60, 0, 0, 10), "degree"));
        t("合成 精修钳 98+10 → clamp 100", () ->
                eq(100, RestorationService.composeDegree(new int[]{98}, 0, 60, 5, 10, 10), "degree"));
        t("合成 满配奖励生效（全部≥60 +5）", () ->
                eq(65, RestorationService.composeDegree(new int[]{60, 60}, 0, 60, 5, 0, 10), "degree"));
        t("合成 有片<60 无满配（40,60→50）", () ->
                eq(50, RestorationService.composeDegree(new int[]{40, 60}, 0, 60, 5, 0, 10), "degree"));
        t("合成 粘合剂 floor 抬升均值（40,60→60）", () ->
                eq(60, RestorationService.composeDegree(new int[]{40, 60}, 60, 60, 0, 0, 10), "degree"));
        t("合成 floor 后满配生效（40,60→65）", () ->
                eq(65, RestorationService.composeDegree(new int[]{40, 60}, 60, 60, 5, 0, 10), "degree"));
        t("合成 clamp 下限 min-integrity（10→10）", () ->
                eq(10, RestorationService.composeDegree(new int[]{10}, 0, 60, 5, 0, 10), "degree"));
        t("合成 双套件叠加（50,50→60均值+5+10=75）", () ->
                eq(75, RestorationService.composeDegree(new int[]{50, 50}, 60, 60, 5, 10, 10), "degree"));
    }

    // ---------- 2. ArtifactCatalog ----------

    private void runCatalog() {
        t("目录 ≥10 件", () -> a(plugin.catalog().all().size() >= 10, "实际 " + plugin.catalog().all().size()));
        t("全部 pieces ≥1", () -> {
            for (Model.ArtifactDef d : plugin.catalog().all()) {
                a(d.pieces() >= 1, d.id() + " pieces=" + d.pieces());
            }
        });
        t("roll(pit) 200 次无 epic/legendary", () -> {
            Random rnd = new Random(42);
            for (int i = 0; i < 200; i++) {
                Model.ArtifactDef d = plugin.catalog().roll(rnd, "pit");
                a(d != null, "roll 返回 null");
                a(!d.rarity().equals("epic") && !d.rarity().equals("legendary"),
                        "pit 档 roll 出 " + d.rarity());
            }
        });
        t("roll 三档均非 null（pit/tomb/palace）", () -> {
            Random rnd = new Random(7);
            a(plugin.catalog().roll(rnd, "pit") != null, "pit null");
            a(plugin.catalog().roll(rnd, "tomb") != null, "tomb null");
            a(plugin.catalog().roll(rnd, "palace") != null, "palace null");
        });
        t("byRarity(common) 非空", () -> a(!plugin.catalog().byRarity("common").isEmpty(), "common 池为空"));
        t("byId 首件可命中", () -> {
            Model.ArtifactDef first = plugin.catalog().all().get(0);
            eq(first.id(), plugin.catalog().byId(first.id()).id(), "byId");
        });
    }

    // ---------- 3. YamlIo ----------

    private void runYamlIo() {
        final File tmp = new File(plugin.getDataFolder(), "selftest-io.tmp.yml");
        t("YamlIo trySave 返回 true", () -> {
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("name", "考古");
            yml.set("count", 123);
            yml.set("enabled", true);
            a(YamlIo.trySave(yml, tmp), "trySave 应为 true");
        });
        t("YamlIo roundtrip name", () -> eq("考古", YamlIo.load(tmp).getString("name"), "name"));
        t("YamlIo roundtrip count", () -> eq(123, YamlIo.load(tmp).getInt("count"), "count"));
        t("YamlIo roundtrip enabled", () -> eq(true, YamlIo.load(tmp).getBoolean("enabled"), "enabled"));
        t("临时文件用完即删", () -> {
            Files.deleteIfExists(tmp.toPath());
            a(!tmp.exists(), "临时文件仍存在");
        });
        t("不存在的文件 load 返回空配置不炸", () ->
                a(YamlIo.load(new File(plugin.getDataFolder(), "selftest-no-such.yml")).getKeys(false).isEmpty(),
                        "应返回空配置"));
    }

    // ---------- 4. DigSiteConfig 与 config.yml 对齐 ----------

    private void runConfig() {
        var c = plugin.digConfig();
        t("config sites.max-active=3", () -> eq(3, c.siteMaxActive(), "max-active"));
        t("config sites.hits-per-layer=6", () -> eq(6, c.hitsPerLayer(), "hits-per-layer"));
        t("config museum.pedestals-per-hall=12", () -> eq(12, c.pedestalsPerHall(), "pedestals-per-hall"));
        t("config museum.vote-period-days=7", () -> eq(7L, c.votePeriodDays(), "vote-period-days"));
        t("config sites.hasty-damage=8", () -> eq(8, c.hastyDamage(), "hasty-damage"));
        t("config sites.brush-cooldown-ticks=15", () -> eq(15, c.brushCooldownTicks(), "brush-cooldown-ticks"));
        t("config restore.broken-degree=60", () -> eq(60, c.brokenDegree(), "broken-degree"));
        t("config restore.perfect-degree=95", () -> eq(95, c.perfectDegree(), "perfect-degree"));
        t("config restore.full-set-bonus=5", () -> eq(5, c.fullSetBonus(), "full-set-bonus"));
        t("config restore.min-integrity=10", () -> eq(10, c.minIntegrity(), "min-integrity"));
        t("config museum.max-halls=6", () -> eq(6, c.museumMaxHalls(), "max-halls"));
        t("config sites.min-distance-between=400", () -> eq(400, c.siteMinDistanceBetween(), "min-distance-between"));
        // E1 新增键
        t("config restore.kits-enabled=true", () ->
                eq(true, c.raw().getBoolean("restore.kits-enabled", true), "kits-enabled"));
        t("config restore.tweezer-bonus=10", () ->
                eq(10, c.raw().getInt("restore.tweezer-bonus", 10), "tweezer-bonus"));
        t("config restore.glue-floor=60", () ->
                eq(60, c.raw().getInt("restore.glue-floor", 60), "glue-floor"));
    }

    // ---------- 5. 命令三处同源 ----------

    private void runCommandSources() {
        t("SUBCOMMANDS 全部出现在 helpLines", () -> {
            String text = String.join("\n", DigCommand.helpLines());
            for (String s : DigCommand.SUBCOMMANDS) {
                a(text.contains(s), "helpLines 缺少 " + s);
            }
        });
        t("ADMIN_SUBS 全部出现在 helpLines", () -> {
            String text = String.join("\n", DigCommand.helpLines());
            for (String s : DigCommand.ADMIN_SUBS) {
                a(text.contains(s), "helpLines 缺少 " + s);
            }
        });
        t("helpLines 非空", () -> a(!DigCommand.helpLines().isEmpty(), "helpLines 为空"));
        t("helpLines 有玩家段与管理员段", () -> {
            String text = String.join("\n", DigCommand.helpLines());
            a(text.contains("玩家指令"), "缺玩家段");
            a(text.contains("管理员指令"), "缺管理员段");
        });
        t("helpLines 每条以 /digs 开头（除段头）", () -> {
            for (String line : DigCommand.helpLines()) {
                String plain = Msg.plain(line);
                a(plain.startsWith("/digs") || plain.contains("——"), "异常行：" + plain);
            }
        });
    }

    // ---------- 6. Model / 数学 ----------

    private void runModelMath() {
        t("SiteCell record 相等（同值）", () -> {
            Model.SiteCell a = new Model.SiteCell(1, -1, 3, 92, false);
            Model.SiteCell b = new Model.SiteCell(1, -1, 3, 92, false);
            a(a.equals(b), "同值 record 应相等");
        });
        t("SiteCell record 不等（值异）", () -> {
            Model.SiteCell a = new Model.SiteCell(1, -1, 3, 92, false);
            Model.SiteCell b = new Model.SiteCell(1, -1, 3, 92, true);
            a(!a.equals(b), "done 不同不应相等");
        });
        t("SiteCell 边界 layer=0 + done=true 相等", () -> {
            Model.SiteCell a = new Model.SiteCell(0, 0, 0, 10, true);
            Model.SiteCell b = new Model.SiteCell(0, 0, 0, 10, true);
            a(a.equals(b), "边界 record 应相等");
        });
        t("periodKeyOf 同周期聚合", () -> {
            eq(periodKeyOf(0, 7), periodKeyOf(6, 7), "第 0~6 天应同周期");
            eq(periodKeyOf(7, 7), periodKeyOf(13, 7), "第 7~13 天应同周期");
        });
        t("periodKeyOf 跨界递增", () ->
                a(!periodKeyOf(6, 7).equals(periodKeyOf(7, 7)), "第 6 与第 7 天应跨周期"));
        t("periodKeyOf 不同 periodDays 分桶不同", () ->
                a(!periodKeyOf(10, 7).equals(periodKeyOf(10, 3)), "周期长度不同键应不同"));
        t("periodKeyOf periodDays≤1 不炸", () ->
                a(periodKeyOf(100, 0).equals(periodKeyOf(100, 1)), "0 应退化为 1"));
        t("periodKeyOf 负 epochDay 不炸", () -> a(periodKeyOf(-30, 7).startsWith("P"), "负数应正常分桶"));
    }

    // ---------- 7. 四个 Store（临时目录，不污染真实存档） ----------

    private void runStores() {
        File dir = new File(plugin.getDataFolder(), "selftest-stores-" + System.nanoTime());
        try {
            runSitesStore(dir);
            runPlayerStore(dir);
            runMuseumStore(dir);
            runCodexStore(dir);
            runAchieveStore(dir);
        } finally {
            deleteRecursively(dir);
        }
    }

    private void runSitesStore(File dir) {
        Model.SiteInfo site = new Model.SiteInfo("ST-TEST", "pit", "world",
                100, -60, 200, 5, 2, 1700000000000L, false);
        t("SitesStore put/get 内存往返", () -> {
            SitesStore s = new SitesStore(dir);
            s.put(site);
            eq("pit", s.get("ST-TEST").tier(), "tier"); // 构造时 tier=pit，id 才是 ST-TEST
        });
        t("SitesStore cells 不可变视图", () -> {
            SitesStore s = new SitesStore(dir);
            s.put(site);
            s.setCells("ST-TEST", List.of(new Model.SiteCell(0, 0, 2, 100, false)));
            try {
                s.cells("ST-TEST").add(null);
                // 走到这里说明 add 成功 → 可变 → 失败
                throw new AssertionError("cells 返回了可变列表");
            } catch (UnsupportedOperationException expected) {
                // 预期：List.copyOf 不可变视图
            }
        });
        t("SitesStore updateCell 按 index", () -> {
            SitesStore s = new SitesStore(dir);
            s.put(site);
            s.setCells("ST-TEST", List.of(
                    new Model.SiteCell(0, 0, 2, 100, false),
                    new Model.SiteCell(1, 0, 2, 100, false)));
            s.updateCell("ST-TEST", 1, new Model.SiteCell(1, 0, 1, 85, false));
            eq(85, s.cells("ST-TEST").get(1).integrity(), "index 更新后 integrity");
        });
        t("SitesStore updateCell 按 dx/dz", () -> {
            SitesStore s = new SitesStore(dir);
            s.put(site);
            s.setCells("ST-TEST", List.of(
                    new Model.SiteCell(0, 0, 2, 100, false),
                    new Model.SiteCell(-1, 2, 2, 100, false)));
            s.updateCell("ST-TEST", new Model.SiteCell(-1, 2, 1, 70, false));
            eq(70, s.cells("ST-TEST").get(1).integrity(), "dx/dz 更新后 integrity");
            eq(100, s.cells("ST-TEST").get(0).integrity(), "邻格不受影响");
        });
        t("SitesStore close 置 closed", () -> {
            SitesStore s = new SitesStore(dir);
            s.put(site);
            s.close("ST-TEST");
            a(s.get("ST-TEST").closed(), "close 后应 closed");
            a(s.allActive().isEmpty(), "allActive 不应含已关站点");
        });
        t("SitesStore flush+重载 落盘往返", () -> {
            SitesStore s = new SitesStore(dir);
            s.put(site);
            s.setCells("ST-TEST", List.of(new Model.SiteCell(2, -2, 1, 63, false)));
            s.flush();
            SitesStore reloaded = new SitesStore(dir);
            Model.SiteInfo back = reloaded.get("ST-TEST");
            a(back != null, "重载后站点丢失");
            eq(200, back.cz(), "cz 往返");
            eq(1, reloaded.cells("ST-TEST").size(), "cells 往返");
            eq(63, reloaded.cells("ST-TEST").get(0).integrity(), "cell.integrity 往返");
        });
    }

    private void runPlayerStore(File dir) {
        UUID u1 = UUID.nameUUIDFromBytes("selftest-1".getBytes());
        UUID u2 = UUID.nameUUIDFromBytes("selftest-2".getBytes());
        t("PlayerStore 零值创建", () -> {
            PlayerStore s = new PlayerStore(dir);
            Model.PlayerStats st = s.stats(u1, "甲");
            eq(0, st.fragmentsFound(), "零值 fragments");
            eq(0, st.donations(), "零值 donations");
        });
        t("PlayerStore bump 系列累计", () -> {
            PlayerStore s = new PlayerStore(dir);
            s.bumpFragments(u1, "甲", 3);
            s.bumpFragments(u1, "甲", 2);
            s.bumpRestored(u1, "甲", 1);
            s.bumpHasty(u1, "甲", 4);
            Model.PlayerStats st = s.stats(u1, "甲");
            eq(5, st.fragmentsFound(), "fragments");
            eq(1, st.artifactsRestored(), "restored");
            eq(4, st.hastyHits(), "hasty");
        });
        t("PlayerStore topDonors 排序", () -> {
            PlayerStore s = new PlayerStore(dir);
            s.bumpDonations(u1, "甲", 1);
            s.bumpDonations(u2, "乙", 9);
            List<Model.PlayerStats> top = s.topDonors(2);
            eq("乙", top.get(0).name(), "榜首应为乙");
            eq(2, top.size(), "榜单长度");
        });
        t("PlayerStore bumpSitesCleared 按名 upsert", () -> {
            PlayerStore s = new PlayerStore(dir);
            s.bumpSitesCleared("丙");
            s.bumpSitesCleared("丙");
            s.bumpSitesCleared("丙");
            List<Model.PlayerStats> hit = s.topDonors(50).stream()
                    .filter(x -> "丙".equalsIgnoreCase(x.name())).toList();
            eq(1, hit.size(), "按名 upsert 不应产生重复档案");
            eq(3, hit.get(0).sitesCleared(), "清剿计数");
        });
        t("PlayerStore flush+重载 往返", () -> {
            PlayerStore s = new PlayerStore(dir);
            s.bumpDonations(u2, "乙", 7);
            s.flush();
            PlayerStore reloaded = new PlayerStore(dir);
            eq(7, reloaded.stats(u2, "乙").donations(), "donations 往返");
            eq("乙", reloaded.stats(u2, "乙").name(), "name 往返");
        });
    }

    private void runMuseumStore(File dir) {
        Model.HallInfo hall = new Model.HallInfo("自然史", "world", 10.5, 64.0, 20.5);
        t("MuseumStore addHall 同名 false", () -> {
            MuseumStore s = new MuseumStore(dir);
            a(s.addHall(hall), "首建应为 true");
            a(!s.addHall(new Model.HallInfo("自然史", "world", 0, 0, 0)), "同名应为 false");
        });
        t("MuseumStore hallByName 命中", () -> {
            MuseumStore s = new MuseumStore(dir);
            s.addHall(hall);
            a(s.hallByName("自然史") != null, "精确名应命中");
        });
        t("MuseumStore setPedestal + firstFreePedestal", () -> {
            MuseumStore s = new MuseumStore(dir);
            s.addHall(hall);
            s.initPedestals("自然史", List.of(
                    new Model.Pedestal(0, 1, 64, 1, null, null, null),
                    new Model.Pedestal(1, 2, 64, 2, null, null, null)));
            a(s.firstFreePedestal("自然史").isPresent(), "应存在空台");
            s.setPedestal("自然史", new Model.Pedestal(0, 1, 64, 1, "EX-A", null, null));
            eq("EX-A", s.pedestals("自然史").get(0).exhibitId(), "setPedestal 替换");
            eq(1, s.firstFreePedestal("自然史").orElseThrow().index(), "剩余空台应为 1 号");
        });
        t("MuseumStore addExhibit 自动生成 EX-8 位 id", () -> {
            MuseumStore s = new MuseumStore(dir);
            String id = s.addExhibit(new Model.ExhibitInfo(null, "bone_flute", 88, "甲", "自然史", 1L));
            a(id.startsWith("EX-") && id.length() == "EX-".length() + 8, "id 格式：" + id);
            eq(id, s.exhibit(id).exhibitId(), "exhibit 命中");
        });
        t("MuseumStore addExhibit 指定 id 保留", () -> {
            MuseumStore s = new MuseumStore(dir);
            eq("EX-FIXED", s.addExhibit(new Model.ExhibitInfo("EX-FIXED", "jade_bi", 60, "乙", "", 2L)),
                    "指定 id 应保留");
        });
        t("MuseumStore vote 一人一轮一票", () -> {
            MuseumStore s = new MuseumStore(dir);
            UUID v = UUID.nameUUIDFromBytes("voter".getBytes());
            a(s.vote("EX-A", "P0", v), "首投应 true");
            a(!s.vote("EX-A", "P0", v), "同轮重复应 false");
            a(s.vote("EX-A", "P1", v), "下一轮应可再投");
        });
        t("MuseumStore votes 统计", () -> {
            MuseumStore s = new MuseumStore(dir);
            s.vote("EX-A", "P0", UUID.nameUUIDFromBytes("a".getBytes()));
            s.vote("EX-A", "P0", UUID.nameUUIDFromBytes("b".getBytes()));
            s.vote("EX-B", "P0", UUID.nameUUIDFromBytes("c".getBytes()));
            eq(2, s.votes("P0").get("EX-A"), "EX-A 两票");
            eq(1, s.votes("P0").get("EX-B"), "EX-B 一票");
            eq(0, s.votes("P1").size(), "无投票轮应空");
        });
        t("MuseumStore removeExhibit 清展台引用", () -> {
            MuseumStore s = new MuseumStore(dir);
            s.addHall(hall);
            s.initPedestals("自然史", List.of(new Model.Pedestal(0, 1, 64, 1, "EX-DEL", null, null)));
            s.addExhibit(new Model.ExhibitInfo("EX-DEL", "oracle_bone", 70, "丙", "自然史", 3L));
            s.removeExhibit("EX-DEL");
            a(s.exhibit("EX-DEL") == null, "展品应被移除");
            a(s.pedestals("自然史").get(0).exhibitId() == null, "展台引用应被清空");
        });
        t("MuseumStore flush+重载 往返", () -> {
            MuseumStore s = new MuseumStore(dir);
            s.addHall(hall);
            s.initPedestals("自然史", List.of(new Model.Pedestal(0, 1.5, 64.0, 1.5, "EX-K", null, null)));
            s.addExhibit(new Model.ExhibitInfo("EX-K", "seal_stamp", 55, "丁", "自然史", 4L));
            s.setLastPeriodKey("P9");
            s.flush();
            MuseumStore reloaded = new MuseumStore(dir);
            eq("自然史", reloaded.hallByName("自然史").name(), "hall 往返");
            eq("EX-K", reloaded.pedestals("自然史").get(0).exhibitId(), "pedestal 往返");
            eq(55, reloaded.exhibit("EX-K").repairDegree(), "exhibit 往返");
            eq("P9", reloaded.lastPeriodKey(), "lastPeriodKey 往返");
        });
    }

    private void runCodexStore(File dir) {
        t("CodexStore putFirstRestoration 只留首个", () -> {
            CodexStore s = new CodexStore(dir);
            a(s.putFirstRestoration(new Model.FirstRestoration("bone_flute",
                    "甲", UUID.nameUUIDFromBytes("a".getBytes()), 1L)), "首记应为 true");
            a(!s.putFirstRestoration(new Model.FirstRestoration("bone_flute",
                    "乙", UUID.nameUUIDFromBytes("b".getBytes()), 2L)), "重复应为 false");
            eq("甲", s.firstRestoration("bone_flute").restorerName(), "首记不应被覆盖");
        });
        t("CodexStore allFirstRestorations 非空", () -> {
            CodexStore s = new CodexStore(dir);
            s.putFirstRestoration(new Model.FirstRestoration("jade_bi", "丙", null, 3L));
            a(!s.allFirstRestorations().isEmpty(), "应至少一条记录");
        });
        t("CodexStore putRecord upsert", () -> {
            CodexStore s = new CodexStore(dir);
            s.putRecord("donations-top", "捐献王", "乙", "9");
            s.putRecord("donations-top", "捐献王", "甲", "12");
            List<Model.RecordEntry> hit = s.records().stream()
                    .filter(r -> r.key().equals("donations-top")).toList();
            eq(1, hit.size(), "upsert 不应重复");
            eq("12", hit.get(0).value(), "value 应被覆盖");
        });
        t("CodexStore bumpSitesCleared 计数", () -> {
            CodexStore s = new CodexStore(dir);
            int before = s.sitesClearedTotal();
            s.bumpSitesCleared();
            s.bumpSitesCleared();
            eq(before + 2, s.sitesClearedTotal(), "计数 +2");
        });
        t("CodexStore flush+重载 往返", () -> {
            CodexStore s = new CodexStore(dir);
            s.putFirstRestoration(new Model.FirstRestoration("seal_stamp", "丁", null, 9L));
            s.putRecord("clears-first", "清剿第一", "丙", "3");
            s.bumpSitesCleared();
            s.flush();
            CodexStore reloaded = new CodexStore(dir);
            eq("丁", reloaded.firstRestoration("seal_stamp").restorerName(), "首修往返");
            eq("3", reloaded.records().stream()
                    .filter(r -> r.key().equals("clears-first")).findFirst().orElseThrow().value(), "纪录往返");
            a(reloaded.sitesClearedTotal() >= 1, "清剿总数往返");
        });
    }

    private void runAchieveStore(File dir) {
        UUID a1 = UUID.nameUUIDFromBytes("achv-1".getBytes());
        t("AchieveStore mark 首次 true", () -> {
            AchieveStore s = new AchieveStore(dir);
            a(s.mark(a1, "first_restore"), "首次达成应 true");
        });
        t("AchieveStore mark 幂等（第二次 false）", () -> {
            AchieveStore s = new AchieveStore(dir);
            s.mark(a1, "first_restore");
            a(!s.mark(a1, "first_restore"), "重复达成应 false");
        });
        t("AchieveStore has/of 往返", () -> {
            AchieveStore s = new AchieveStore(dir);
            s.mark(a1, "vote_first");
            s.mark(a1, "site_3");
            a(s.has(a1, "vote_first"), "has 应命中");
            a(!s.has(a1, "donate_5"), "未达成不应命中");
            eq(2, s.of(a1).size(), "of 数量");
            a(s.of(UUID.nameUUIDFromBytes("achv-none".getBytes())).isEmpty(), "无记录玩家 of 应空");
        });
        t("AchieveStore 计数器 bump/get", () -> {
            AchieveStore s = new AchieveStore(dir);
            eq(1L, s.bumpCounter("visit:EX-A"), "首次 +1");
            eq(2L, s.bumpCounter("visit:EX-A"), "再次 +1");
            eq(0L, s.getCounter("visit:EX-B"), "无关 key 应 0");
        });
        t("AchieveStore 计数器 key 相互独立", () -> {
            AchieveStore s = new AchieveStore(dir);
            s.bumpCounter("perfect:" + a1);
            s.bumpCounter("perfect:" + a1);
            s.bumpCounter("perfect:" + a1);
            eq(3L, s.getCounter("perfect:" + a1), "同 key 累计");
            eq(0L, s.getCounter("common:" + a1 + ":bone_flute"), "异 key 独立");
        });
        t("AchieveStore flush+重载 成就集往返", () -> {
            AchieveStore s = new AchieveStore(dir);
            s.mark(a1, "epic_first");
            s.mark(a1, "legendary_first");
            s.mark(UUID.nameUUIDFromBytes("achv-2".getBytes()), "kit_master");
            s.flush();
            AchieveStore reloaded = new AchieveStore(dir);
            a(reloaded.has(a1, "epic_first"), "epic_first 往返");
            a(reloaded.has(a1, "legendary_first"), "legendary_first 往返");
            a(reloaded.has(UUID.nameUUIDFromBytes("achv-2".getBytes()), "kit_master"), "他人成就往返");
            a(!reloaded.mark(a1, "epic_first"), "重载后幂等仍成立");
        });
        t("AchieveStore flush+重载 计数器往返", () -> {
            AchieveStore s = new AchieveStore(dir);
            s.bumpCounter("visit:EX-K");
            s.bumpCounter("visit:EX-K");
            s.bumpCounter("visit:EX-K");
            s.flush();
            AchieveStore reloaded = new AchieveStore(dir);
            eq(3L, reloaded.getCounter("visit:EX-K"), "visit 计数往返");
        });
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        try {
            Files.deleteIfExists(f.toPath());
        } catch (Exception ignored) {
            // 自检临时目录清理失败不影响主流程
        }
    }

    /** 供外部（如调试）确认失败明细；无副作用。 */
    public List<String> lastFailures() {
        return List.copyOf(failures);
    }
}
