"""
verify_watershed.py
===================

Faithful Python port of `BuildingLocator.locateBed` (VillagerAgent, v4 rewrite,
see docs/vision_module_design.md §5.6 / vision_module_implementation.md).

Goal: verify whether the **distance-field + watershed** room segmentation actually
confines a house when the house has a HOLE (opening to the outside). The user
suspects water "spills out" through the hole.

What is ported (1:1 with the Java source):
  Step 0  overhead-cover pre-filter          (hasOverheadCover)
  Step 1  3D connected-air flood (sub-lattice SUBDIV=2)   -> inC
  Step 2  3D distance field (multi-source BFS from solids) -> D
  Step 3  skyOpen / bigAir atmosphere metrics
  Step 4  watershed: descending priority-flood by D; each local max seeds a basin
  Step 5  atmosphere test (hasSky || bigAirFraction>BIG_AIR_FRACTION) -> drop basin
  Step 6  union all kept basins -> house AABB

A *strict* variant is also provided: a basin is a room only if it is also NOT
touching the scan-box boundary (the usual signature of an exterior that the hole
opened into). This demonstrates a fix for the spill.

Constants match the Java file (SCAN_RADIUS etc.). SCAN_RADIUS is lowered to 16
here purely for demo speed; the spill/drop logic is identical at 24.

Usage:
    python verify_watershed.py
"""

import heapq

# ---- constants (identical to BuildingLocator.java) -------------------------
SCAN_RADIUS   = 16    # Java: 24  (lowered here only to keep the demo fast)
V_BELOW       = 2
V_UP          = 16
SUBDIV        = 2
AIR_RUN       = 4
BIG_AIR_FRACTION = 0.85
MIN_ROOM      = 8


# ---------------------------------------------------------------------------
# Synthetic world: a 3D grid of "solid" booleans.
# solid=True  == isWall() in Java (non-air, non-liquid block)
# ---------------------------------------------------------------------------
class GridWorld:
    def __init__(self, sx, sy, sz):
        self.sx, self.sy, self.sz = sx, sy, sz
        self.solid = bytearray(sx * sy * sz)  # 0 = air, 1 = wall

    def _idx(self, x, y, z):
        return (x * self.sy + y) * self.sz + z

    def set(self, x, y, z, v=True):
        if 0 <= x < self.sx and 0 <= y < self.sy and 0 <= z < self.sz:
            self.solid[self._idx(x, y, z)] = 1 if v else 0

    def fill(self, x0, y0, z0, x1, y1, z1, v=True):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.set(x, y, z, v)

    def is_wall(self, x, y, z):
        if not (0 <= x < self.sx and 0 <= y < self.sy and 0 <= z < self.sz):
            return True  # out of world == solid (don't leak)
        return self.solid[self._idx(x, y, z)] == 1

    def get_block_state(self, x, y, z):
        # emulate net.minecraft.block.BlockState: material AIR vs other
        class _St:
            pass
        st = _St()
        st._solid = self.is_wall(x, y, z)
        return st


# ---------------------------------------------------------------------------
# Builders
# ---------------------------------------------------------------------------
def add_room(world, x0, x1, z0, z1, y0, y1, openings=()):
    """
    Build a closed box: shell on x0,x1,z0,z1 (y in [y0,y1]), floor at y0-1,
    roof at y1+1, all spanning the footprint. Interior air = (x0+1..x1-1) x
    (y0..y1) x (z0+1..z1-1). `openings` is a list of (x,y,z) cells carved out
    of the shell (doors / holes / windows).
    """
    # vertical walls
    for y in range(y0, y1 + 1):
        for z in range(z0, z1 + 1):
            world.set(x0, y, z); world.set(x1, y, z)
        for x in range(x0, x1 + 1):
            world.set(x, y, z0); world.set(x, y, z1)
    # floor + roof
    world.fill(x0, y0 - 1, z0, x1, y0 - 1, z1)
    world.fill(x0, y1 + 1, z0, x1, y1 + 1, z1)
    # carve openings
    for (x, y, z) in openings:
        world.set(x, y, z, False)


# ---------------------------------------------------------------------------
# Faithful port of BuildingLocator.locateBed
# ---------------------------------------------------------------------------
def neighbors(cur, LX, LY, LZ, LYZ):
    ck = cur % LZ
    cj = (cur // LZ) % LY
    ci = cur // LYZ
    r = [0] * 6
    r[0] = cur + 1 if ck < LZ - 1 else -1
    r[1] = cur - 1 if ck > 0 else -1
    r[2] = cur + LZ if cj < LY - 1 else -1
    r[3] = cur - LZ if cj > 0 else -1
    r[4] = cur + LYZ if ci < LX - 1 else -1
    r[5] = cur - LYZ if ci > 0 else -1
    return r


def has_overhead_cover(world, bed):
    for dy in range(1, V_UP + 1):
        p = (bed[0], bed[1] + dy, bed[2])
        st = world.get_block_state(*p)
        if st._solid:  # isRoof == isWall in this port
            return True
    return False


def compute_big_air(inC, solid, LX, LY, LZ, LYZ, air_run=AIR_RUN):
    out = [False] * len(inC)

    # X axis: run over i (x), fix j (y) and k (z)
    for j in range(LY):
        for k in range(LZ):
            fwd = [0] * LX; bwd = [0] * LX
            for i in range(LX):
                idx = (i * LY + j) * LZ + k
                fwd[i] = (fwd[i - 1] + 1) if (i > 0 and inC[idx]) else (1 if inC[idx] else 0)
            for i in range(LX - 1, -1, -1):
                idx = (i * LY + j) * LZ + k
                bwd[i] = (bwd[i + 1] + 1) if (i < LX - 1 and inC[idx]) else (1 if inC[idx] else 0)
            for i in range(LX):
                idx = (i * LY + j) * LZ + k
                if inC[idx] and fwd[i] >= air_run and bwd[i] >= air_run:
                    out[idx] = True

    # Y axis: run over j (y), fix i (x) and k (z)
    for i in range(LX):
        for k in range(LZ):
            fwd = [0] * LY; bwd = [0] * LY
            for j in range(LY):
                idx = (i * LY + j) * LZ + k
                fwd[j] = (fwd[j - 1] + 1) if (j > 0 and inC[idx]) else (1 if inC[idx] else 0)
            for j in range(LY - 1, -1, -1):
                idx = (i * LY + j) * LZ + k
                bwd[j] = (bwd[j + 1] + 1) if (j < LY - 1 and inC[idx]) else (1 if inC[idx] else 0)
            for j in range(LY):
                idx = (i * LY + j) * LZ + k
                if inC[idx] and fwd[j] >= air_run and bwd[j] >= air_run:
                    out[idx] = True

    # Z axis: run over k (z), fix i (x) and j (y)
    for i in range(LX):
        for j in range(LY):
            fwd = [0] * LZ; bwd = [0] * LZ
            for k in range(LZ):
                idx = (i * LY + j) * LZ + k
                fwd[k] = (fwd[k - 1] + 1) if (k > 0 and inC[idx]) else (1 if inC[idx] else 0)
            for k in range(LZ - 1, -1, -1):
                idx = (i * LY + j) * LZ + k
                bwd[k] = (bwd[k + 1] + 1) if (k < LZ - 1 and inC[idx]) else (1 if inC[idx] else 0)
            for k in range(LZ):
                idx = (i * LY + j) * LZ + k
                if inC[idx] and fwd[k] >= air_run and bwd[k] >= air_run:
                    out[idx] = True

    return out


# ---------------------------------------------------------------------------
# Shared setup (Steps 0-3): scan box, sub-lattice, air flood, distance field,
# skyOpen + bigAir. Both the buggy and the fixed watershed reuse this.
# ---------------------------------------------------------------------------
def _setup(world, bed, air_run=AIR_RUN):
    if not has_overhead_cover(world, bed):
        return None, "no overhead cover"

    R = SCAN_RADIUS
    xMin = bed[0] - R; xMax = bed[0] + R
    yMin = max(0, bed[1] - V_BELOW); yMax = min(world.sy - 1, bed[1] + V_UP)
    zMin = bed[2] - R; zMax = bed[2] + R
    SX = xMax - xMin + 1; SY = yMax - yMin + 1; SZ = zMax - zMin + 1
    if SX <= 0 or SY <= 0 or SZ <= 0:
        return None, "bad box"

    blockSolid = [[[world.is_wall(xMin + lx, yMin + ly, zMin + lz)
                    for lz in range(SZ)] for ly in range(SY)] for lx in range(SX)]

    LX = SX * SUBDIV; LY = SY * SUBDIV; LZ = SZ * SUBDIV; LYZ = LY * LZ; N = LX * LY * LZ
    solid = [False] * N
    for i in range(LX):
        for j in range(LY):
            for k in range(LZ):
                solid[(i * LY + j) * LZ + k] = blockSolid[i // SUBDIV][j // SUBDIV][k // SUBDIV]

    # Step 1: connected-air flood from the bed
    bi = (bed[0] - xMin) * SUBDIV; bj = (bed[1] - yMin) * SUBDIV; bk = (bed[2] - zMin) * SUBDIV
    startIdx = -1
    for d in [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]:
        ni, nj, nk = bi + d[0], bj + d[1], bk + d[2]
        if 0 <= ni < LX and 0 <= nj < LY and 0 <= nk < LZ:
            idx = (ni * LY + nj) * LZ + nk
            if not solid[idx]:
                startIdx = idx; break
    if startIdx == -1:
        return None, "bed buried"

    inC = [False] * N
    q = [startIdx]; inC[startIdx] = True
    while q:
        cur = q.pop()
        for n in neighbors(cur, LX, LY, LZ, LYZ):
            if n >= 0 and not solid[n] and not inC[n]:
                inC[n] = True; q.append(n)

    # Step 2: distance field (multi-source BFS from solids)
    D = [-1] * N
    dq = [i for i in range(N) if solid[i]]
    for i in dq:
        D[i] = 0
    head = 0
    while head < len(dq):
        cur = dq[head]; head += 1
        d = D[cur]
        for n in neighbors(cur, LX, LY, LZ, LYZ):
            if n >= 0 and D[n] == -1:
                D[n] = d + 1; dq.append(n)

    # Step 3: skyOpen + bigAir
    skyOpen = [False] * N
    for i in range(LX):
        for k in range(LZ):
            above = True
            for j in range(LY - 1, -1, -1):
                idx = (i * LY + j) * LZ + k
                if solid[idx]:
                    above = False; skyOpen[idx] = False
                else:
                    skyOpen[idx] = above and inC[idx]
    bigAir = compute_big_air(inC, solid, LX, LY, LZ, LYZ, air_run=air_run)
    for i in range(N):
        bigAir[i] = inC[i] and (skyOpen[i] or bigAir[i])

    m = dict(xMin=xMin, yMin=yMin, zMin=zMin, SX=SX, SY=SY, SZ=SZ,
             SUBDIV=SUBDIV, LYZ=LYZ, LX=LX, LY=LY, LZ=LZ, N=N,
             inC=inC, solid=solid, D=D, skyOpen=skyOpen, bigAir=bigAir,
             bi=bi, bj=bj, bk=bk)
    return m, None


# ---------------------------------------------------------------------------
# BUGGY watershed (verbatim port of BuildingLocator.java Step 4).
# "First assigned neighbor wins" on a descending flood. Because the open-air
# (exterior) basin has the highest distance-field maximum, it is processed
# FIRST and engulfs interior cells through the opening; the boundary is placed
# arbitrarily inside the room, not at the doorway ridge.
# ---------------------------------------------------------------------------
def watershed_original(inC, D, skyOpen, bigAir, LX, LY, LZ, LYZ, N):
    basin = [-1] * N
    sizes = []; bigAirCounts = []; hasSky = []; touchesBoundary = []
    pq = []
    for i in range(N):
        if inC[i]:
            heapq.heappush(pq, (-D[i], i))
    while pq:
        _, cur = heapq.heappop(pq)
        if basin[cur] != -1:
            continue
        b = -1
        for n in neighbors(cur, LX, LY, LZ, LYZ):
            if n >= 0 and basin[n] != -1:
                b = basin[n]; break
        if b == -1:
            b = len(sizes)
            sizes.append(0); bigAirCounts.append(0); hasSky.append(False); touchesBoundary.append(False)
        basin[cur] = b
        sizes[b] += 1
        if bigAir[cur]:
            bigAirCounts[b] += 1
        if skyOpen[cur]:
            hasSky[b] = True
        ci = cur // LYZ; cj = (cur // LZ) % LY; ck = cur % LZ
        if ci == 0 or ci == LX - 1 or cj == 0 or cj == LY - 1 or ck == 0 or ck == LZ - 1:
            touchesBoundary[b] = True
        for n in neighbors(cur, LX, LY, LZ, LYZ):
            if n >= 0 and inC[n] and basin[n] == -1:
                heapq.heappush(pq, (-D[n], n))
    return basin, sizes, hasSky, bigAirCounts, touchesBoundary


# ---------------------------------------------------------------------------
# FIXED watershed: proper marker-controlled (Meyer) watershed.
# Markers = regional maxima of D (one per connected plateau top). Cells whose
# labelled neighbours belong to >=2 different basins become WATERSHED boundary
# pixels (label -2) instead of being absorbed. The boundary then falls exactly
# at the doorway ridge, so the open-air basin can no longer engulf the room.
# ---------------------------------------------------------------------------
WSHED = -2


def watershed_meyer(inC, D, skyOpen, bigAir, LX, LY, LZ, LYZ, N):
    basin = [-1] * N
    # seed markers at regional maxima (plateau components with no higher-D neighbour)
    visited = [False] * N
    next_label = 0
    pq = []
    for cur in range(N):
        if not inC[cur] or visited[cur]:
            continue
        # regional max? any inC neighbour strictly greater -> not a max
        is_max = True
        for n in neighbors(cur, LX, LY, LZ, LYZ):
            if n >= 0 and inC[n] and D[n] > D[cur]:
                is_max = False; break
        if not is_max:
            continue
        # flood the equal-D plateau component, label it all with one marker
        stack = [cur]; visited[cur] = True; comp = []
        while stack:
            c = stack.pop(); comp.append(c)
            for n in neighbors(c, LX, LY, LZ, LYZ):
                if n >= 0 and inC[n] and not visited[n] and D[n] == D[cur]:
                    visited[n] = True; stack.append(n)
        for c in comp:
            basin[c] = next_label
        for c in comp:
            heapq.heappush(pq, (-D[c], c))
        next_label += 1

    sizes = [0] * next_label; bigAirCounts = [0] * next_label
    hasSky = [False] * next_label; touchesBoundary = [False] * next_label

    # `queued` guarantees each cell is enqueued at most once -> the flood
    # terminates and runs in O(N log N). Without it, WSHED pixels re-push
    # their neighbours every time they are popped, which blows the queue up.
    queued = [False] * N

    def push(n):
        if n >= 0 and inC[n] and basin[n] == -1 and not queued[n]:
            queued[n] = True
            heapq.heappush(pq, (-D[n], n))

    for c in range(N):
        if basin[c] >= 0:
            for n in neighbors(c, LX, LY, LZ, LYZ):
                push(n)

    it = 0
    cap = 10 * N
    while pq:
        it += 1
        if it > cap:
            labelled = sum(1 for b in basin if b != -1)
            raise RuntimeError("Meyer loop exceeded cap: it=%d pq=%d labelled=%d/%d next_label=%d" %
                               (it, len(pq), labelled, N, next_label))
        _, cur = heapq.heappop(pq)
        lab = basin[cur]
        if lab != -1:
            continue  # already a marker / assigned -> nothing to decide
        # cur is an unlabelled pixel just popped (pushed by a labelled neighbour).
        # Decide its label from its currently-labelled neighbours. Because the
        # queue is ordered by DESCENDING D, low-D cells (doorways / ridges) are
        # popped last, so both basins have reached the ridge by now and the
        # boundary falls exactly where >=2 basins meet.
        ml = set()
        for n in neighbors(cur, LX, LY, LZ, LYZ):
            if n >= 0 and inC[n] and basin[n] >= 0:
                ml.add(basin[n])
        if len(ml) == 1:
            basin[cur] = ml.pop()
        else:
            basin[cur] = WSHED  # >=2 basins, or none yet -> watershed boundary
        for n in neighbors(cur, LX, LY, LZ, LYZ):
            push(n)
    # turn watershed lines into "unassigned" so they are excluded from rooms
    for i in range(N):
        if basin[i] == WSHED:
            basin[i] = -1
    # recompute per-basin stats from the final labelling
    sizes = [0] * next_label; bigAirCounts = [0] * next_label
    hasSky = [False] * next_label; touchesBoundary = [False] * next_label
    for i in range(N):
        b = basin[i]
        if b < 0:
            continue
        sizes[b] += 1
        if bigAir[i]:
            bigAirCounts[b] += 1
        if skyOpen[i]:
            hasSky[b] = True
        ci = i // LYZ; cj = (i // LZ) % LY; ck = i % LZ
        if ci == 0 or ci == LX - 1 or cj == 0 or cj == LY - 1 or ck == 0 or ck == LZ - 1:
            touchesBoundary[b] = True
    return basin, sizes, hasSky, bigAirCounts, touchesBoundary


# ---------------------------------------------------------------------------
# Shared classifier (Steps 5/6): per-basin atmosphere test + AABB union.
# ---------------------------------------------------------------------------
def classify_and_aabb(m, basin, sizes, hasSky, bigAirCounts, touchesBoundary, strict):
    inC = m["inC"]; bigAir = m["bigAir"]; D = m["D"]
    xMin, yMin, zMin = m["xMin"], m["yMin"], m["zMin"]
    SX, SY, SZ, SUBDIV, LYZ = m["SX"], m["SY"], m["SZ"], m["SUBDIV"], m["LYZ"]
    LX, LY, LZ, N = m["LX"], m["LY"], m["LZ"], m["N"]
    bi, bj, bk = m["bi"], m["bj"], m["bk"]

    roomBlock = [[[False] * SZ for _ in range(SY)] for _ in range(SX)]
    minWX = minWY = minWZ = 10**9
    maxWX = maxWY = maxWZ = -10**9
    roomBlocks = 0; maxRoomD = 0
    basin_debug = []
    basin_sx = [0.0] * len(sizes); basin_sy = [0.0] * len(sizes); basin_sz = [0.0] * len(sizes)
    bed_basin = basin[(bi * LY + bj) * LZ + bk] if inC[(bi * LY + bj) * LZ + bk] else -1
    for b in range(len(sizes)):
        frac = (bigAirCounts[b] / sizes[b]) if sizes[b] else 0.0
        is_room = (not hasSky[b]) and frac <= BIG_AIR_FRACTION
        if strict:
            is_room = is_room and (not touchesBoundary[b])
        basin_debug.append({
            "basin": b, "size": sizes[b], "hasSky": hasSky[b],
            "bigAirFrac": round(frac, 3), "touchesBoundary": touchesBoundary[b],
            "isRoom": is_room,
        })
    for i in range(N):
        if basin[i] < 0 or not inC[i]:
            continue
        b = basin[i]
        keep = (not hasSky[b]) and ((bigAirCounts[b] / sizes[b]) if sizes[b] else 1) <= BIG_AIR_FRACTION
        if strict:
            keep = keep and (not touchesBoundary[b])
        if not keep:
            continue
        ci = i // LYZ; cj = (i // LZ) % LY; ck = i % LZ
        bx = ci // SUBDIV; by = cj // SUBDIV; bz = ck // SUBDIV
        basin_sx[b] += bx; basin_sy[b] += by; basin_sz[b] += bz
        if not roomBlock[bx][by][bz]:
            roomBlock[bx][by][bz] = True
            roomBlocks += 1
            wx, wy, wz = xMin + bx, yMin + by, zMin + bz
            minWX = min(minWX, wx); maxWX = max(maxWX, wx)
            minWY = min(minWY, wy); maxWY = max(maxWY, wy)
            minWZ = min(minWZ, wz); maxWZ = max(maxWZ, wz)
        if D[i] > maxRoomD:
            maxRoomD = D[i]

    for b in range(len(sizes)):
        if sizes[b]:
            basin_debug[b]["centroid"] = (round(basin_sx[b] / sizes[b], 1),
                                          round(basin_sy[b] / sizes[b], 1),
                                          round(basin_sz[b] / sizes[b], 1))

    if roomBlocks < MIN_ROOM:
        return None, {"rejected": "too few room blocks", "basins": basin_debug, "bedBasin": bed_basin}

    aabb_min = (minWX, minWY, minWZ)
    aabb_max = (maxWX, maxWY, maxWZ)
    rec = {
        "bed": None, "aabb_min": aabb_min, "aabb_max": aabb_max,
        "maxRoomD": maxRoomD / SUBDIV, "roomBlocks": roomBlocks,
    }
    return rec, {"basins": basin_debug, "aabb": (aabb_min, aabb_max), "bedBasin": bed_basin}


def locate_bed(world, bed, strict=False):
    """Original (buggy) port of BuildingLocator.locateBed."""
    m, reason = _setup(world, bed, air_run=AIR_RUN)
    if m is None:
        return None, {"rejected": reason}
    basin, sizes, hasSky, bigAirCounts, touchesBoundary = watershed_original(
        m["inC"], m["D"], m["skyOpen"], m["bigAir"], m["LX"], m["LY"], m["LZ"], m["LYZ"], m["N"])
    return classify_and_aabb(m, basin, sizes, hasSky, bigAirCounts, touchesBoundary, strict)


def locate_bed_fixed(world, bed, strict=False, air_run=12):
    """Proposed fix: Meyer watershed + sane AIR_RUN (12 lattice = 6 blocks)."""
    m, reason = _setup(world, bed, air_run=air_run)
    if m is None:
        return None, {"rejected": reason}
    basin, sizes, hasSky, bigAirCounts, touchesBoundary = watershed_meyer(
        m["inC"], m["D"], m["skyOpen"], m["bigAir"], m["LX"], m["LY"], m["LZ"], m["LYZ"], m["N"])
    return classify_and_aabb(m, basin, sizes, hasSky, bigAirCounts, touchesBoundary, strict)


# ---------------------------------------------------------------------------
# Scenario runner
# ---------------------------------------------------------------------------
def run_scenario(name, world, bed, expected_interior, strict=False, locator=locate_bed):
    rec, dbg = locator(world, bed, strict=strict)
    print(f"\n=== {name}  (strict={strict}) ===")
    if rec is None:
        print("  RESULT: NOT A HOUSE  (rejected:", dbg.get("rejected"), ")")
        for b in dbg.get("basins", []):
            print(f"    basin {b['basin']:>2}: size={b['size']:>6} "
                  f"hasSky={str(b['hasSky']):>5} bigAirFrac={b['bigAirFrac']:.3f} "
                  f"touchBox={b['touchesBoundary']} -> room={b['isRoom']}")
        return
    aabb = (rec["aabb_min"], rec["aabb_max"])
    print(f"  detected AABB: min={rec['aabb_min']} max={rec['aabb_max']}  "
          f"roomBlocks={rec['roomBlocks']} maxRoomD={rec['maxRoomD']:.1f}")
    for b in dbg["basins"]:
        c = b.get("centroid")
        cstr = f"centroid={c}" if c is not None else ""
        print(f"    basin {b['basin']:>2}: size={b['size']:>6} "
              f"hasSky={str(b['hasSky']):>5} bigAirFrac={b['bigAirFrac']:.3f} "
              f"touchBox={b['touchesBoundary']} -> room={b['isRoom']} {cstr}")
    print(f"  bed is in basin: {dbg.get('bedBasin')}")
    # spill check: does detected AABB extend beyond expected interior in any axis?
    ex_min, ex_max = expected_interior
    spill = any(aabb[0][a] < ex_min[a] for a in range(3)) or any(aabb[1][a] > ex_max[a] for a in range(3))
    print(f"  expected interior: min={ex_min} max={ex_max}")
    print(f"  SPILL (extends beyond interior through hole): {spill}")
    return spill


def main():
    # ---------------- Scenario A: normal house with a DOOR (control) -------
    # footprint x[20,30] z[20,30], interior y[10,13]; door = 1-wide gap
    wA = GridWorld(70, 40, 70)
    add_room(wA, 20, 30, 20, 30, 10, 13,
             openings=[(25, 10, 20), (25, 11, 20)])  # 1x2 door on south wall
    bedA = (25, 10, 25)
    expA = ((21, 10, 21), (29, 13, 29))
    run_scenario("A: enclosed house w/ door (control)", wA, bedA, expA)

    # ---------------- Scenario B: hole to OPEN exterior ---------------------
    wB = GridWorld(70, 40, 70)
    add_room(wB, 20, 30, 20, 30, 10, 13,
             openings=[(25, 11, 20)])  # single 1x1 hole in south wall
    bedB = (25, 10, 25)
    expB = ((21, 10, 21), (29, 13, 29))
    run_scenario("B: hole (1x1) -> open exterior", wB, bedB, expB)

    # ---------------- Scenario C: hole to COVERED nook (suspected spill) ----
    wC = GridWorld(70, 40, 70)
    add_room(wC, 20, 30, 20, 30, 10, 13,
             openings=[(25, 11, 20)])  # 1x1 hole in south wall
    # build a ROOFED nook just outside the hole: x[14,19] z[21,26] y[10,13]
    # walls on the 4 sides + roof, but OPEN toward the house at x=20 (the hole)
    for y in range(10, 14):
        for z in range(21, 27):
            wC.set(14, y, z)            # west wall of nook
        for x in range(14, 20):
            wC.set(x, y, 21); wC.set(x, y, 26)  # north/south walls of nook
    wC.fill(14, 9, 21, 19, 9, 26)       # nook floor
    wC.fill(14, 14, 21, 19, 14, 26)     # nook roof
    # leave x=20 side open (that's the hole); the nook interior is x[15,19] z[22,25] y[10,13]
    bedC = (25, 10, 25)
    expC = ((21, 10, 21), (29, 13, 29))  # true house should NOT include the nook
    run_scenario("C: hole (1x1) -> COVERED nook (suspected spill)", wC, bedC, expC)

    # ---------------- Scenario D: skylight (hole in roof) -------------------
    wD = GridWorld(70, 40, 70)
    add_room(wD, 20, 30, 20, 30, 10, 13,
             openings=[(25, 14, 25)])  # 1x1 hole in roof
    bedD = (25, 10, 25)
    expD = ((21, 10, 21), (29, 13, 29))
    run_scenario("D: skylight (1x1 hole in roof)", wD, bedD, expD)

    # ---------------- Scenario C strict: same geometry, strict containment --
    run_scenario("C(strict): hole -> covered nook, with box-boundary fix", wC, bedC, expC, strict=True)

    # ---------------- Scenario E: SMALL house + covered nook (reproduce spill)
    # Interior is only 3x3x3 => NOT mostly "big air", so the interior basin is
    # kept; a 1x1 hole in the SOUTH wall opens into a roofed nook => that nook
    # basin is also kept (covered, not hasSky, not >85% big air) => AABB extends
    # through the hole. Nook sits SOUTH of the hole (z[20,23]) so it connects.
    wE = GridWorld(70, 40, 70)
    add_room(wE, 24, 28, 24, 28, 10, 12, openings=[(26, 11, 24)])  # 5x5 outer, 3x3 interior, hole at (26,11,24)
    # roofed nook south of the hole: interior x[25,28] z[20,23] y[10,12]
    for y in range(10, 13):
        for x in range(25, 29):
            wE.set(x, y, 20)                       # south wall of nook
        for z in range(20, 24):
            wE.set(25, y, z); wE.set(28, y, z)     # west/east walls of nook
    wE.fill(25, 9, 20, 28, 9, 23)                  # nook floor
    wE.fill(25, 13, 20, 28, 13, 23)                # nook roof
    # north side z=24 is open (toward the house hole at (26,11,24)) -> connected
    bedE = (26, 10, 26)
    expE = ((25, 10, 25), (27, 12, 27))            # true small house only
    run_scenario("E: SMALL house + hole -> COVERED nook (spill repro)", wE, bedE, expE)

    # ---------------- FIXED watershed: re-run A, B, C, E with Meyer + AIR_RUN=12
    print("\n" + "#" * 70)
    print("#  FIXED (Meyer watershed + sane AIR_RUN=12) -- same scenarios")
    print("#" * 70)
    run_scenario("A(fixed): enclosed house w/ door", wA, bedA, expA, locator=locate_bed_fixed)
    run_scenario("B(fixed): hole -> open exterior", wB, bedB, expB, locator=locate_bed_fixed)
    run_scenario("C(fixed): hole -> covered nook", wC, bedC, expC, locator=locate_bed_fixed)
    run_scenario("E(fixed): small house + covered nook", wE, bedE, expE, locator=locate_bed_fixed)


if __name__ == "__main__":
    main()
