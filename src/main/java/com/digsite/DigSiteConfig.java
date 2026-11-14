package com.digsite;

import org.bukkit.configuration.file.FileConfiguration;

/** config.yml 只读视图。所有 getter 带默认值，配置缺失不炸。 */
public final class DigSiteConfig {
    private final FileConfiguration raw;

    public DigSiteConfig(FileConfiguration raw) {
        this.raw = raw;
    }

    public FileConfiguration raw() {
        return raw;
    }

    // general
    public String prefix() {
        return raw.getString("general.prefix", "<gray>[</gray><gradient:#c98f2d:#7a4d17>考古</gradient><gray>]</gray> ");
    }

    // sites
    public int siteMaxActive() {
        return raw.getInt("sites.max-active", 3);
    }

    public long siteSpawnIntervalMinutes() {
        return raw.getLong("sites.spawn-interval-minutes", 120L);
    }

    public int siteMinDistanceBetween() {
        return raw.getInt("sites.min-distance-between", 400);
    }

    public int siteMinDistanceFromSpawn() {
        return raw.getInt("sites.min-distance-from-spawn", 300);
    }

    public int brushCooldownTicks() {
        return raw.getInt("sites.brush-cooldown-ticks", 15);
    }

    public int hastyDamage() {
        return raw.getInt("sites.hasty-damage", 8);
    }

    public int hitsPerLayer() {
        return raw.getInt("sites.hits-per-layer", 6);
    }

    public long siteExpireDays() {
        return raw.getLong("sites.expire-days", 14L);
    }

    public int spawnAttempts() {
        return raw.getInt("sites.spawn-attempts", 24);
    }

    // museum
    public int museumMaxHalls() {
        return raw.getInt("museum.max-halls", 6);
    }

    public int pedestalsPerHall() {
        return raw.getInt("museum.pedestals-per-hall", 12);
    }

    public double pedestalRadius() {
        return raw.getDouble("museum.pedestal-radius", 5.0);
    }

    public long votePeriodDays() {
        return raw.getLong("museum.vote-period-days", 7L);
    }

    public boolean announceEpicPlus() {
        return raw.getBoolean("museum.announce-epic-plus", true);
    }

    // restore
    public int minIntegrity() {
        return raw.getInt("restore.min-integrity", 10);
    }

    public int perfectDegree() {
        return raw.getInt("restore.perfect-degree", 95);
    }

    public int brokenDegree() {
        return raw.getInt("restore.broken-degree", 60);
    }

    public int fullSetBonus() {
        return raw.getInt("restore.full-set-bonus", 5);
    }

    // announce
    public long announceThrottleSeconds() {
        return raw.getLong("announce.throttle-seconds", 3L);
    }
}
