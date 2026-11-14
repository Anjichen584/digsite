package com.digsite;

import com.digsite.catalog.ArtifactCatalog;
import com.digsite.codex.CodexService;
import com.digsite.cmd.DigCommand;
import com.digsite.cmd.SelfTest;
import com.digsite.museum.MuseumService;
import com.digsite.museum.VoteService;
import com.digsite.restore.RestorationService;
import com.digsite.site.ExcavationListener;
import com.digsite.site.SiteScheduler;
import com.digsite.site.SiteService;
import com.digsite.store.CodexStore;
import com.digsite.store.MuseumStore;
import com.digsite.store.PlayerStore;
import com.digsite.store.SitesStore;
import com.digsite.util.Announcer;
import com.digsite.util.ItemCodec;
import com.digsite.util.Msg;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Random;
import java.util.logging.Level;

/**
 * 考古博物馆 · DigSite 主类。
 * 初始化顺序（后建的依赖先建的）：stores → announcer → codex → museum → vote → restoration → site → listeners/command/scheduler。
 */
public final class DigSitePlugin extends JavaPlugin {
    private DigSiteConfig config0;
    private ItemCodec codec;
    private ArtifactCatalog catalog;
    private Random random;

    private SitesStore sitesStore;
    private PlayerStore playerStore;
    private MuseumStore museumStore;
    private CodexStore codexStore;

    private Announcer announcer;
    private CodexService codexService;
    private MuseumService museumService;
    private VoteService voteService;
    private RestorationService restorationService;
    private SiteService siteService;
    private SiteScheduler siteScheduler;
    private SelfTest selfTest;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config0 = new DigSiteConfig(getConfig());
        Msg.setPrefix(config0.prefix());
        codec = new ItemCodec(this);
        random = new Random();
        catalog = new ArtifactCatalog(new File(getDataFolder(), "artifacts.yml"));
        saveDefaultResource("artifacts.yml");

        sitesStore = new SitesStore(getDataFolder());
        playerStore = new PlayerStore(getDataFolder());
        museumStore = new MuseumStore(getDataFolder());
        codexStore = new CodexStore(getDataFolder());

        announcer = new Announcer(config0.announceThrottleSeconds());
        announcer.start(this);

        codexService = new CodexService(this, codexStore, announcer, codec, catalog, config0);
        museumService = new MuseumService(this, museumStore, codec, announcer, catalog, config0);
        voteService = new VoteService(this, museumStore, playerStore, codexService, announcer, config0);
        restorationService = new RestorationService(this, codec, catalog, playerStore, codexService, config0);
        siteService = new SiteService(this, sitesStore, catalog, codec, codexService, playerStore, config0);

        getServer().getPluginManager().registerEvents(new ExcavationListener(this, siteService, playerStore), this);

        selfTest = new SelfTest(this);
        DigCommand digCommand = new DigCommand(this, selfTest);
        PluginCommand pc = getCommand("digs");
        if (pc != null) {
            pc.setExecutor(digCommand);
            pc.setTabCompleter(digCommand);
        }

        siteScheduler = new SiteScheduler(this, siteService);
        siteScheduler.start();

        museumService.respawnMissing();

        // 启动即跑一轮快速自检，只把失败写日志（不打扰玩家）
        int failed = selfTest.run(null);
        if (failed > 0) {
            getLogger().log(Level.WARNING, "启动自检存在失败项，请尽快 /digs selftest 查看详情。失败数=" + failed);
        } else {
            getLogger().info("启动自检通过：断言 " + selfTest.lastTotal() + " 条，失败 0。");
        }
        getLogger().info("DigSite 已启用：文物 " + catalog.all().size() + " 件，馆藏 " + museumStore.exhibitCount() + " 件。");
    }

    @Override
    public void onDisable() {
        if (siteScheduler != null) siteScheduler.stop();
        if (siteService != null) siteService.shutdown();
        if (museumService != null) museumService.shutdown();
        if (announcer != null) announcer.stop();
        if (sitesStore != null) sitesStore.flush();
        if (playerStore != null) playerStore.flush();
        if (museumStore != null) museumStore.flush();
        if (codexStore != null) codexStore.flush();
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        config0 = new DigSiteConfig(getConfig());
        Msg.setPrefix(config0.prefix());
    }

    private void saveDefaultResource(String name) {
        File f = new File(getDataFolder(), name);
        if (!f.exists()) saveResource(name, false);
    }

    // ---------- 访问器 ----------

    public DigSiteConfig digConfig() {
        return config0;
    }

    public ItemCodec codec() {
        return codec;
    }

    public ArtifactCatalog catalog() {
        return catalog;
    }

    public Random random() {
        return random;
    }

    public SitesStore sitesStore() {
        return sitesStore;
    }

    public PlayerStore playerStore() {
        return playerStore;
    }

    public MuseumStore museumStore() {
        return museumStore;
    }

    public CodexStore codexStore() {
        return codexStore;
    }

    public Announcer announcer() {
        return announcer;
    }

    public CodexService codexService() {
        return codexService;
    }

    public MuseumService museumService() {
        return museumService;
    }

    public VoteService voteService() {
        return voteService;
    }

    public RestorationService restorationService() {
        return restorationService;
    }

    public SiteService siteService() {
        return siteService;
    }

    public SelfTest selfTest() {
        return selfTest;
    }
}
