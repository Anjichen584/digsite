package com.digsite.museum;

import com.digsite.DigSiteConfig;
import com.digsite.DigSitePlugin;
import com.digsite.model.Model;
import com.digsite.store.MuseumStore;
import com.digsite.store.PlayerStore;
import com.digsite.codex.CodexService;
import com.digsite.util.Announcer;
import com.digsite.util.Msg;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 镇馆之宝投票服务（C 部分）。
 *
 * <p>对 MuseumStore（代理A 实现）调用的投票相关 API：
 * <pre>
 *   Model.ExhibitInfo exhibit(String exhibitId)
 *   boolean vote(String exhibitId, String periodKey, java.util.UUID voter)  // 同期同展品已投过返回 false
 *   java.util.Map&lt;String, Integer&gt; votes(String periodKey)                  // exhibitId → 票数
 *   String lastPeriodKey()                                                  // null = 从未结算过
 *   void setLastPeriodKey(String key)
 * </pre>
 */
public final class VoteService {

    private final DigSitePlugin plugin;
    private final MuseumStore store;
    private final PlayerStore playerStore;
    private final CodexService codexService;
    private final Announcer announcer;
    private final DigSiteConfig config;

    public VoteService(DigSitePlugin plugin, MuseumStore store, PlayerStore playerStore,
                       CodexService codexService, Announcer announcer, DigSiteConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.playerStore = playerStore;
        this.codexService = codexService;
        this.announcer = announcer;
        this.config = config;
    }

    /**
     * 纯函数：周期键 = "P" + floorDiv(epochDay, periodDays)。
     * 供结算与自检（SelfTest 可直接静态调用校验边界）。
     */
    public static String periodKeyOf(long epochDay, long periodDays) {
        long safePeriod = Math.max(1L, periodDays);
        return "P" + Math.floorDiv(epochDay, safePeriod);
    }

    /** 当前周期键（今天 + vote-period-days）。 */
    public String currentPeriodKey() {
        return periodKeyOf(LocalDate.now().toEpochDay(), config.votePeriodDays());
    }

    /** /digs vote &lt;exhibitId&gt;：先结算到期轮次，再记票。每人每轮每展品一票（存档为准）。 */
    public void vote(Player p, String exhibitId) {
        Model.ExhibitInfo ex = store.exhibit(exhibitId);
        if (ex == null) {
            Msg.send(p, "<red>没有这个展品编号。</red><gray>编号可在 /digs museum 展品详情里查看。</gray>");
            return;
        }
        settleIfDue(); // 投票前先把到期轮次结算掉，保证键位正确

        String key = currentPeriodKey();
        UUID voter = p.getUniqueId();
        if (!store.vote(exhibitId, key, voter)) {
            Msg.send(p, "<yellow>本轮你已经投过这件展品了。</yellow><gray>每轮每人一票。</gray>");
            return;
        }
        Msg.send(p, "<green>投票成功！</green><gray>你把票投给了「</gray><gold>"
                + safeName(ex.artifactId()) + "</gold><gray>」。</gray>");
        // 成就回调（E2）：vote_first
        if (plugin.achieveService() != null) plugin.achieveService().checkVote(p);
        // 按契约：领先不实时播报，仅成功提示；轮末结算统一公告
    }

    /**
     * 到期结算：lastPeriodKey != currentKey 时结算上一轮——
     * votes(lastKey) 最高票 exhibit 获胜（并列取最早捐赠）；胜者走 codexService.recordVoteWinner
     * （其内部已做 putRecord + 公告，此处不重复公告）。无票/无存续展品则静默推进键位。
     */
    public void settleIfDue() {
        String current = currentPeriodKey();
        String last = store.lastPeriodKey();
        if (current.equals(last)) return;

        if (last != null) {
            Map<String, Integer> tally = store.votes(last);
            if (tally != null && !tally.isEmpty()) {
                String winnerId = null;
                int best = Integer.MIN_VALUE;
                long earliest = Long.MAX_VALUE;
                for (Map.Entry<String, Integer> e : tally.entrySet()) {
                    Model.ExhibitInfo ex = store.exhibit(e.getKey());
                    if (ex == null) continue; // 已被移除的展品不参评
                    // 并列票数取最早捐赠（donatedAt 更小者优先）
                    if (e.getValue() > best || (e.getValue() == best && ex.donatedAt() < earliest)) {
                        best = e.getValue();
                        earliest = ex.donatedAt();
                        winnerId = e.getKey();
                    }
                }
                if (winnerId != null) {
                    Model.ExhibitInfo ex = store.exhibit(winnerId);
                    codexService.recordVoteWinner(winnerId, safeName(ex.artifactId()), ex.donorName());
                }
            }
        }
        store.setLastPeriodKey(current);
    }

    private String safeName(String artifactId) {
        com.digsite.model.Model.ArtifactDef def = plugin.catalog().byId(artifactId);
        return def == null ? artifactId : def.name();
    }
}
