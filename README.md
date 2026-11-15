# 考古博物馆 · DigSite v0.2.0

Minecraft **1.21.11**（Paper，已在 1.21.10 真机验证）生存服考古玩法插件，完全独立（不读不写任何其他插件的存档与 API）。
完整链路：**野外落遗址 → 分层刷挖 → 碎片掉落 → 拼图修复 → 捐入博物馆 → 图鉴留名 / 纪录墙 / 镇馆之宝投票**。

- 目标 Java：**21+**（编译 release 21）
- 构建产物：`target/DigSite-0.2.0.jar`
- 无第三方依赖，纯 Paper API + MiniMessage
- `config-version`：1
- 零 RPG：玩家无等级/属性/代币；奖励全原版物品

## 一、功能模块

| 模块 | 文件 | 简介 |
| --- | --- | --- |
| 遗址生成 | `site/SiteService` + `sites.yml` | 三档（小型灰坑 5x5/2层、贵族墓 7x7/3层、宫殿基址 9x9/4层），随机落点（距出生点/其他遗址有距离约束），可疑砂/砾分层柱 + 石底座 |
| 挖掘状态机 | `site/ExcavationListener` | 手持刷子右键可疑格：拦截原版战利品 → 本插件计次削层；同格手速过快扣格子 integrity（碎片成色变差）；每层完成 roll 文物掉碎片 |
| 遗址保护 | 同上 | 考古区禁止破坏/爆炸（admin 可同步清除）；全格挖完 → 遗址关闭 + 全服公告 + 清剿结算 |
| 遗址调度 | `site/SiteScheduler` | 每分钟一查：活跃数 < max-active 且到 spawn-interval → 自动落遗址；超 expire-days 风化关闭；每 5 分钟冲刷存档 |
| 碎片/成品编解码 | `util/ItemCodec` | 全身份走 PDC：碎片 BRICK 四键，成品按 artifacts.yml display 材质 + 成色/修复者 Lore |
| 拼图修复 | `restore/RestorationService` + `RestoreMenu` | 集齐同一文物全部片 → 拼图 GUI 指派 → repairDegree = 各片均值（全片 ≥ 完整线 + 满配奖励），<60 残缺 / 60-94 完整 / ≥95 无损（无损全服公告） |
| 博物馆 | `museum/MuseumService` | `/digs admin hall add` 脚下环形生成 12 展台（石英柱 + ItemDisplay 托文物 + TextDisplay 铭牌）；捐献陈列；重启 `respawnMissing` 补实体 |
| 图鉴/纪录 | `codex/CodexService` | 每件文物全服首修永久留名；首件 rare/epic/legendary 纪录、清剿总数、镇馆之宝、馆藏数 |
| 投票 | `museum/VoteService` | `periodKeyOf` 周期键；每人每轮一票，到期自动结算上轮并公告 |
| 存档 | `store/*` + `util/YamlIo` | 4 份存档全走 tmp→fsync→ATOMIC_MOVE 原子写，坏档 `.corrupt-<ts>` 留档；脏标记 + 定期冲刷 + 关服冲刷 |
| 公告 | `util/Announcer` | 队列 + 节流，epic+ 插队广播 |
| 自检 | `cmd/SelfTest` | **78 条断言**（codec 往返/目录/原子写/命令三处同源/周期键/成色分档…），启动自动跑 + `/digs selftest` 手动 |

## 二、命令

```
玩家：/digs help | board | codex [页] | museum | donate | vote <展品ID> | stats [玩家]
管理：/digs admin spawn <tier> | tp <siteId> | close <siteId> | hall add <名> | hall list | exhibit remove <id> | reload
自检：/digs achieve           成就 GUI（已达成绿旗/未达成灰书）
/digs selftest          （控制台可用 help/board/stats/selftest）
```

## 三、构建与部署

```
mvn -B clean package
# 产物 target/DigSite-0.1.0.jar → 服务端 plugins/
```

## 四、真机验证记录（2026-11-15，D:\game\1.21.10server paper-1.21.10-130）

- 启动自检 **78/78 失败 0**（首轮 75/78，修 3 处后全绿：SelfTest 两处断言笔误、MuseumStore.addExhibit 生成 id 未写回 record）
- RCON 冒烟：help/board/admin spawn 全通，遗址 `Smwgsmxu4` 落地 world(-124,1543)
- `execute if block` 实测柱体结构：113 可疑砂 / 112 SAND / 111 STONE 底座 / 114 清空 AIR / 邻格柱一致
- 残留物：测试用 scoreboard `digchk` 已删；测试遗址留在场上可直接进服刷挖体验

## 五、真机验证（e2e bot）

`tools/e2e-backup.js`（运行副本在 ForgingMaster-Lore/tools/guitest，连 `DIGSITE_PORT` 指定端口）：
**16/16 PASS** —— 刷挖 300 交互 → 6 文物碎片 PDC 识别 → 修复台拼图 → 无损修复公告 → 建馆 → 捐献展台陈列 → 成就触发（藏而好施）→ 图鉴 GUI → 统计/纪录墙。
专属测试服：`testserver/`（paper 1.21.10，端口 25577，RCON 25576/`digsite-test`，在线模式关）。
专项实测副本：`tools/feature-backup.js`（盗墓贼断言为 0.35/2min 概率项，失败可重跑；服务端已实证）。

## 六、维护纪律（继承铸剑项目经验）

1. 新存档一律走 `util.YamlIo`，裸 `yml.save` 不许出现
2. 命令三处同源：`SUBCOMMANDS` Tab 表 + `helpLines()` + `onCommand` 分派，漏一处自检红
3. admin 命令 handler 内自判 `digs.admin`，Tab 隐藏不算
4. 自检断言数只许增不许减（当前 **78**）；删功能需在此处写一行说明
5. 改动收尾五步：`mvn -B clean package` → 部署 testserver → `/digs selftest` 失败 0 → 更新本表加一行 → 代码文档同 commit 推

| 版本 | 一句话 |
| --- | --- |
| **0.2.0c** | 专项实测 12/12 确定性项全绿（wreck 沉船+顶块材质 / glacier 顶层+冰壳 8/8 方位 / 双 bot 并发清场恰一次关场 / 双 bot 各有猎获 / 传说遗物 BossBar 触发）；盗墓贼单独实证（日志：盗墓贼 x1 袭扰，roll 0.285 pass）；SiteGuard.spawnOne 改 boolean + 刷扰运营日志 |
| **0.2.0b** | 真机 e2e 修 2 条 P1：RestoreMenu 会话在 openInventory 触发的 onClose 中被清（拼图页点击全失效）；Paper 对 cancelled 点击事后重发原槽内容压制增量渲染（指派改整窗重开 + 保留已指派） |
| **0.2.0** | P2 双轨拓展：**D 遗址纵深**（沉船遗骸/冰封祭坛新档位 + 冰壳通用机制 + 盗墓贼夜袭 + 传说遗物全服 BossBar）+ **E 修复·馆藏·成就**（精修钳/粘合剂套件 + 12 条成就 `/digs achieve` GUI + 参观计数 + 拓片纪念品不可再捐）+ 自检 78→108 条 |
| **0.1.0** | 首版：遗址三档生成 + 挖掘状态机 + 修复拼图 + 博物馆 + 图鉴/投票 + 78 断言自检，真机全绿 |
