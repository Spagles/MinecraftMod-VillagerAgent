# VillagerAgent：房屋检测实现记录（当前落地版本）

> 配套设计文档：`docs/building_perception_design.md`（关系型结构解析的抽象方案）。
> 本文记录**已经落到代码里的** Stage-1 房屋检测与索引实现的真实做法——类、方法、常量、失效机制、已知坑，方便后续维护与二次开发。
>
> 涉及源码：
> - `ai/vision/BuildingLocator.java` — 单床 → 建筑记录（距离场 + Meyer 分水岭）
> - `ai/world/WorldStructureIndex.java` — 全维度建筑索引、事件驱动失效、队列式重扫
> - `ai/world/BuildingRecord.java` — 建筑记录（支持多床）
> - `ai/world/StructureIndexSavedData.java` — 索引持久化（`WorldSavedData`）
> - `events/VillagerEventHandler.java` — Forge 事件订阅 → 索引失效触发
> - `ai/VillagerAgentManager.java` — 每 tick 驱动 `processPending`
>
> 验证脚本：`AI scripts/building_detection_verify/verify_watershed.py`（分水岭/Meyer 对照）、`AI scripts/building_detection_verify/verify_update.py`（索引更新机制模拟）。

---

## 1. 总览

```
Forge 事件 (BlockEvent / ChunkLoad)
        │  offerBed / indexChunk / onBlockChanged / onBedRemoved
        ▼
WorldStructureIndex  ──(每tick processPending: FLOODS_PER_TICK=1)──►  BuildingLocator.locateBed(bed)
   byId / byChunk            ▲ 一次洪泛                       │ 距离场 + Meyer 分水岭
   claimedBeds              │ 一床只扫一次                    ▼
   rejectedBeds             └────────────── add / remove / markDirty ──►  BuildingRecord (多床)
   pending                                                       │
   (持久化到 StructureIndexSavedData)                            ▼
                                                      村民消费：queryNear() / getAt()
```

核心职责拆分：
- **`BuildingLocator`** 回答"**给定一张床，它所在的封闭建筑长什么样**"（粗粒度：AABB、类型、多床集合）。
- **`WorldStructureIndex`** 回答"**世界里有哪些建筑、任意坐标属于哪栋**"，并把发现过程做成**事件驱动、队列式、一次扫一次**的增量索引，跨重启持久化。
- 村民（`VillagerVisionSystem` / `VillagerAgentData`）只调用 `queryNear` / `getAt`，**自己从不对世界做洪泛**。

---

## 2. `BuildingLocator.locateBed`：单床 → 建筑

### 2.1 关键常量

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `SCAN_RADIUS` | `24` | 水平半扫描盒（房子足迹），±24 格 |
| `V_BELOW` | `2` | 床下方扫描层数（地下室） |
| `V_UP` | `16` | 床上方扫描层数（多层楼；3D 洪泛处理） |
| `SUBDIV` | `2` | 每方块轴 2 个采样点 → 0.5 格分辨率（每块 8 子体素） |
| `AIR_RUN` | `12` | 一个细胞在某轴两向各有 ≥12 连通空气才算 "big air"。**本版由 4 升到 12** |
| `BIG_AIR_FRACTION` | `0.85` | 盆地中 big-air 占比 > 此值 → 判定为大气（非房间） |
| `MIN_ROOM` | `8` | 房间块数少于此 → 不算房子，返回 `null` |

> **`AIR_RUN` 4→12 是头号修复**：`AIR_RUN=4`（2 格）会让普通房间 ~100% 被读成 big air，frac≈1.0 > 0.85 → 整屋被当"开阔大气"丢弃；升到 12（6 格）后只有真正开阔空间才命中，房间保留。
> 证据见 `AI scripts/building_detection_verify/verify_watershed.py`：Scenario A（普通 9×9 房+门）原版仅检出 **63/324** 块，修复后 **326/324** 满房。

### 2.2 执行步骤（函数内注释编号有轻微重叠，以下按功能描述）

0. **顶部遮挡预筛** `hasOverheadCover`：沿床正上方 `V_UP` 层查非空气非液体块（玻璃也算顶），找不到 → 直接 `return null`（露天床，便宜早退；大气测试还会再兜一层）。
1. **建盒 + 逐块实心度**：在 ±24 / 竖直范围内每方块一次 `getBlockState`，`isWall` 判定实心。
2. **下采样子栅格**：`SUBDIV=2` 把每方块扩成 2×2×2 子体素（0.5 格分辨率，对应设计文档要的"8 子体素/块"）。
3. **3D 连通空气洪泛**：从床的 6 邻接空气出发，6 邻接 flood，得到"床可达的连通空气体" `inC`。
4. **3D 距离场**：多源 BFS，从所有实心格出发，每个空气格记录"到最近墙的步数" `D`。封闭空间离墙最远点 = 房间中心。
5. **big-air 信号**：`computeBigAir` 按 X/Y/Z 三轴，对 `inC` 内细胞算"两向各有 ≥AIR_RUN 连续空气"；再并上 `skyOpen`（该列竖直通透到天空）。`bigAir[i] = inC[i] && (skyOpen[i] || bigAir[i])`。
6. **Meyer 标记控制分水岭**（详见 §3）：把 `D` 的区域极大值当种子盆地，门口相遇处筑 `WSHED=-2` 边界，挡住室外经洞漏入室内。
7. **盆地分类 → 房间并集**：每个盆地若 `!hasSky` 且 `bigAirFrac ≤ BIG_AIR_FRACTION` 即为房间；所有房间块并集出 AABB（仅覆盖空气格）。
8. **包围盒外扩**：AABB 在 **±x / ±z 各 +1**，把 1 格厚墙体壳也包进屋子，并 **夹到扫描盒**（`xMin..xMax` / `zMin..zMax`）不越界。

返回 `BuildingRecord(id, seedBed, 外扩后 bounds, sealRadius=maxRoomD/SUBDIV, roomBlocks, coarseType)`。
`coarseType` 由 `classifyType` 给出：`roomBlock` 外接的实心块 ≥50% 嵌入岩石（6 邻居中 ≥5 实心）→ `cave_house`，否则 `house`。

### 2.3 阻挡物定义（花盆已确认是墙）

```java
private static boolean isWall(BlockState st) {
    Material m = st.getMaterial();
    return m != Material.AIR && !m.isLiquid();
}
private static boolean isRoof(BlockState st) {
    return st.getMaterial() != Material.AIR && !st.getMaterial().isLiquid();
}
```

规则：**只要不是空气、不是液体，就算墙/可遮挡**。据此：

- ✅ **花盆**（`Material.DECORATION`）——非空气非液体 → 算墙，洪泛被挡。你担心的"花盆是否算阻挡物"答案是**已经是，无需改**。
- ✅ 箱子、台阶、火把、牌子、地毯、铁轨等装饰块同样被算作墙（通常无害）。
- ⚠️ 反向提醒：玻璃、树叶这类"透明但实心"的块**也**算墙（设计决定：只有流体可穿过）；若日后想让薄装饰件可穿过，需加"放行清单（denylist）"。

---

## 3. 距离场分水岭（Meyer）详解

### 3.1 为什么用 Meyer（而非原版"先到先得"）

把 `D` 当地形海拔：离墙越近海拔越低，房间中心/室外中心海拔最高。分水岭 = 模拟涨水。

- **原版（已被替换）**：所有 `inC` 细胞进优先队列、按 `D` 降序出队，每个细胞取"第一个有标签邻居"为盆地。门口是低-D 瓶颈、最后出队，室外（hasSky，D 最大、先处理）经洞口把近门室内细胞抢走 → 床可能掉进 `hasSky` 盆地（Scenario A 实测如此）。
- **Meyer（当前）**：种子取 `D` 的**区域极大值**（连通等高平台 = 一个种子盆地）；一个细胞若同时邻接 ≥2 个不同盆地 → 标 `WSHED=-2` 边界（排除），边界精确落在门口脊线，室外不再漏入。
- **额外修复**：每个细胞**只入队一次**（`queued` 守卫）。原版 `pq.add(n)` 不防重复入队，跑全 33³ 扫描盒会队列爆炸卡死；现整批 9 场景 23s 跑完。

### 3.2 算法骨架（`BuildingLocator` 第 227–309 行）

```
1. 扫描区域极大值：inC 内 D 不小于所有邻居者，连同其等-D 连通平台，标同一 basin 编号，并入优先队列。
2. 把每个已标 basin 的未标邻居入队一次（queued 守卫）。
3. 主循环：弹出细胞；
     若已标 → 跳过；
     否则统计已标邻居的 basin 集合：
       - 仅 1 个 basin → 归入该 basin；
       - ≥2 个 basin → 标 WSHED(-2)，排除；
       - 无已标邻居（理论上不会发生）→ 标 WSHED。
     把它的未标 inC 邻居入队一次。
4. 洪泛结束后，按最终 labeling 重算各 basin 的 sizes / hasSky / bigAirCounts
   （WSHED 细胞 basin[i]<0，被下游 classify 自然跳过）。
```

> `WSHED=-2`、`basin[i]>=0` 是房间、`basin[i]<0` 是边界或未分配。下游 classify（第 317 行）用 `basin[i] < 0` 排除边界，无需改别处。

### 3.3 分水岭修不掉的部分（固有局限）

Meyer 只管"边界画在脊线"，不管"两个合格盆地该不该并成同一栋"。若洞连通的是**有顶暗格**（非 sky、小到不算 big air），两个盆地都通过大气测试，Step 7 一并并入 AABB → 仍溢出（验证脚本 Scenario E）。根治需另加**连通颈宽判定**（见 §7 待办）。

---

## 4. 建筑索引与更新机制 `WorldStructureIndex`

### 4.1 数据结构

```
byId:        Map<long id, BuildingRecord>          // 主表，id = seedBed.asLong() & 0x7FFF...
byChunk:     Map<chunkLong, List<BuildingRecord>>  // 按包围盒覆盖的 chunk 反查，加速 getAt/queryNear
claimedBeds: Set<long>        // 已属于某建筑的床（永不再扫）
rejectedBeds:Set<long>        // 扫过但不在房子里的床（负缓存）
pending:     Deque<long>      // 待洪泛的床；pendingSet 去重
```

持久化：`byId + 三个床集合` 全部写入 `StructureIndexSavedData`（经 `WorldSavedData`），重启不重扫。`BuildingRecord.writeNBT/readNBT` 用 `ListNBT(LongNBT)` 存多床（`beds` 字段；旧存档无该字段时 `readNBT` 回退到 `seedBed`，向后兼容）。

### 4.2 增量发现（无周期全扫）

- **chunk 加载** → `VillagerEventHandler` 订阅 `ChunkEvent.Load` → `indexChunk(chunk)` → 从 chunk 的 block-entity 表读床（`BuildingLocator.bedsInChunk`，零方块扫描）→ `offerBed`。
- **玩家放/拆方块** → `BlockEvent.EntityPlaceEvent` / `BreakEvent` → `offerBed`（床）/ `onBedRemoved`（床）/ `onBlockChanged`（其它块）。
- **每 tick** → `VillagerAgentManager.tickAgents` → `processPending(world, FLOODS_PER_TICK=1)`：每 tick 最多 1 次洪泛，整村发现摊到几秒内，不卡服。

### 4.3 失效 / 重扫路径

```
方块变化 (onBlockChanged)
   → getAt(pos) 找到所属建筑 → remove(id) + enqueue(seedBed)   // 该建筑丢弃，种子床重扫
   → 找不到 → 12 格内被拒床复活重扫（可能刚补上最后一道墙）

床被拆 (onBedRemoved)
   → 找到含此床的记录 → removeBed(bed)
        · 床集空 → remove(整屋)              // 最后一张床没了 = 整屋消失
        · 拆的是 seedBed → 提升存活床为新 seedBed（id 不变）
        · 否则仅移除该床
   → 不在任何记录 → 清 claimed/rejected/pending 缓存

整屋重扫 (processPending)
   · 周边 ±SCAN_RADIUS chunk 未全加载 → 该床出队，留待下次 chunk 加载/采样重入队
   · 床已不属于任何建筑(claimed)或已在记录内 → 若记录内则 addBed 绑定同一屋，跳过
   · 否则 locateBed → add(record) / rejectedBeds.add(key)
```

> **效果**：结构性改动（敲墙、开门、玩家手放/拆方块）会触发重扫更新——这条机制是接通的、可用的。

### 4.4 已知缺陷（更新机制的真实坑）

| # | 缺陷 | 现象 | 根因 / 位置 | 严重度 |
| --- | --- | --- | --- | --- |
| G1 | **拆床 = 整屋删除** | 床一拆，壳还在也认不出整栋 | 每栋只锚定种子床；`onBedRemoved` 删 `byId`+清缓存。无"无床也能识屋" | 中 |
| G2 | **内部变化不可见** | 屋里放箱/家具，记录无变化 | `BuildingRecord` 只存粗 AABB+type+seedBed+多床；无内部模型；重扫结果通常相同 | 中 |
| G3 | **机械改动不失效** | 活塞推墙、爆炸、液体流动后索引陈旧 | 这些**不触发 `BlockEvent`**；而 `markDirty` 是**死代码（全工程无调用方）**，本应作此用途 | 高 |
| G4 | **未加载即丢弃（backoff）** | 周边 chunk 未加载时该床出队 | `processPending` 第 280 行 `if (!chunksLoaded) continue;` 出队后**不加回**；仅当后续 chunk 加载或采样再次 `offerBed` 才重试。静态已加载小区若扫描盒不全加载则延迟/漏检 | 低-中 |

> **关于 G3**：`markDirty(AxisAlignedBB)` 方法已写好（作用：把重叠建筑删除并重扫、复活 SCAN_RADIUS 内被拒床），但**没有任何调用点**——活塞/爆炸/液体应在此处调用它才能生效。

### 4.5 多床合并（一间房两张床 → 同一栋）

`BuildingRecord` 持有 `List<BlockPos> beds`（`seedBed` 始终是首个/稳定 id 源；`beds[0]` 恒含 seedBed）。

- **`add`**：新建记录时，包围盒内其它待处理床直接 `addBed` 绑进同一屋。
- **`processPending`**：一张床开始检测时若 `getAt(bed) != null`（已在某屋包围盒内）→ 把它 `addBed` 到那个屋，**而非像旧版只标记 claimed 后丢弃**。这正是"发现床已在已有房子包围盒内"的场景。
- **`onBedRemoved` / `onBedRemovedInternal`**：按"哪个记录含此床"移除；删最后一张床→整屋消失；删的是 seedBed→自动提升存活床为新 seed（id 不变，索引不错位）。

行为：9×9 房两床 → 先定位的床生成整屋（包围盒已含两床连通空气），后定位的床发现自己在盒内 → 并入同一 `BuildingRecord.beds`，两床同属一栋。

---

## 5. 村庄 POI 结论（未实现增强）

查 1.16.5 真实 API：
- 每张床生成/放置时即写入 `minecraft:home` POI，存于 `ServerWorld.getPoiManager()`。
- 本模组 `bedsInChunk` 在 chunk 加载时**直接从方块实体表读床**——拿到的就是同一份数据，且比回头查 `PoiManager` 更省（不强制加载 section）。
- 1.16.5 村庄系统**不存"每栋房的 AABB"**，房屋边界由 POI 簇动态推导，无单调用 API 能直接拿到"某村所有房子的坐标盒"。

结论：**"认出村庄里的房子并把床绑过去"已被现有"床驱动发现 + §4.5 多床合并"覆盖**。直读 `PoiType.HOME` 仅在"想给建筑打村庄分组 tag"时有价值——属可选增强，当前未做。

---

## 6. 验证脚本

| 脚本 | 验证内容 | 关键结论 |
| --- | --- | --- |
| `AI scripts/building_detection_verify/verify_watershed.py` | `locateBed` 距离场 + 分水岭的忠实 Python 移植 + Meyer 对照 | 原版对有孔房溢出（A 仅 63/324、床落 hasSky 盆地）；Meyer+AIR_RUN=12 → 满房 326/324、B/C 无误检、E 仍溢出（固有局限） |
| `AI scripts/building_detection_verify/verify_update.py` | `WorldStructureIndex` 缓存/失效机制的 Python 模拟 + 6 场景 | 确认 G1–G4 四类"不更新"缺口（拆床删整屋 / 内部无模型 / 机械改动无失效 `markDirty` 死代码 / 未加载即丢弃） |

> 注：`AI scripts/building_detection_verify/verify_update.py` 的 `locate_bed` 替身为简化不过门口（整盒洪泛成 `open`），仅用于压测**缓存层**逻辑，不影响 G1–G4 结论（那四个缺口与几何检测无关）。

---

## 7. 已知问题与待办

- [ ] **G3（高）**：接上 `markDirty`——`PistonEvent`、爆炸、`/setblock /fill`、液体流动后调用。
- [ ] **G4**：`processPending` 在 chunk 未加载时**重新入队（backoff 计数）**而非丢弃，避免静态小区漏检。
- [ ] **G1**：给"无床也识屋"机制（shell-only / 多锚点），让拆床不丢整屋。
- [ ] **E 溢出**：分水岭固有局限，洞口连通有顶暗格时两盆地都合格 → 加"连通颈宽判定"（1×1 洞作隔断/壁橱，≥2 格才算门）。
- [ ] **可选**：村庄 POI 直读，给建筑打村庄分组 tag。
- [ ] **反向提示**：当前规则把火把/牌子/地毯/铁轨等薄装饰也当墙；如需可穿过，加放行清单。

---

## 8. 编译 / 验证备忘

按约定，**AI 不跑 `gradlew` 以省 token**，由用户本地验证：

```
gradlew compileJava
```

重点盯：
1. `BuildingRecord.writeNBT/readNBT` 的 `Constants.NBT.TAG_LONG` / `LongNBT.valueOf` / `ListNBT.add` 映射名（1.16.5 标准，应无误）。
2. 多床 NBT 读写与旧存档兼容（`readNBT` 已对缺 `beds` 字段回退到 `seedBed`）。
3. 包围盒外扩后，相邻房屋的 `getAt` 会不会误并（外扩 +1 在相邻建筑贴合时有小概率重叠，必要时按 id 最近优先）。
4. 游戏内实测：有门/有洞房子 bed 识别，拆一张床（多床屋应保留、删最后一张消失），活塞推墙后索引是否过期（确认 G3 仍待修）。
