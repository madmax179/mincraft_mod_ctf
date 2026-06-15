package ru.madmax.tf1.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import ru.madmax.tf1.TF1Mod;
import ru.madmax.tf1.data.TF1WorldData;
import ru.madmax.tf1.registry.ModBlockEntities;
import ru.madmax.tf1.team.TF1Team;
import ru.madmax.tf1.team.TeamManager;
import ru.madmax.tf1.util.TF1Messages;

import javax.annotation.Nullable;
import java.util.*;

public class CapturePointBlockEntity extends BlockEntity {

    // NBT keys
    private static final String TAG_POINT_ID      = "point_id";
    private static final String TAG_CAPTURED_BY   = "captured_by";
    private static final String TAG_CAPTURING_TEAM = "capturing_team";
    private static final String TAG_CAPTURE_TICKS  = "capture_ticks";

    private static final DustParticleOptions NEUTRAL_DUST =
            new DustParticleOptions(new Vector3f(0.8f, 0.8f, 0.8f), 1.0f);

    /** Persistent local ID shared by the whole connected cluster. 0 = not yet assigned. */
    private int pointId = 0;

    @Nullable private TF1Team capturedBy   = null; // persisted
    @Nullable private TF1Team capturingTeam = null; // transient
    private int captureTicks = 0;                   // transient

    // Boss bar — transient, server-side only, never serialized
    @Nullable private transient ServerBossEvent captureBar = null;
    @Nullable private transient TF1Team lastBarTeam = null;

    public CapturePointBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CAPTURE_POINT_BE.get(), pos, state);
    }

    // -------------------------------------------------------------------------
    // Tick — server-side only
    // -------------------------------------------------------------------------

    public static void tick(Level level, BlockPos pos, BlockState state, CapturePointBlockEntity be) {
        if (level.isClientSide) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        List<CapturePointBlockEntity> group = findGroup(level, pos);
        BlockPos canonicalPos = getCanonical(group);

        if (!canonicalPos.equals(pos)) {
            // Non-canonical: only spawn particles
            spawnParticles(level, pos, be);
            return;
        }

        // === CANONICAL BLOCK LOGIC ===

        // 1. Resolve point ID (assigned once, then propagated to all members)
        if (be.pointId == 0) {
            int existingId = 0;
            for (CapturePointBlockEntity member : group) {
                if (member.pointId > 0) { existingId = member.pointId; break; }
            }
            be.pointId = (existingId > 0)
                    ? existingId
                    : TF1WorldData.get(serverLevel).allocatePointId();
            // Register this point in the global registry (owner may already be known)
            TF1WorldData.get(serverLevel).updatePointOwner(be.pointId, be.capturedBy);
            be.setChanged();
        }
        // Propagate point ID to every member that is out of sync.
        // Track IDs that are being abandoned due to a cluster merge so they can be
        // removed from the global registry.
        Set<Integer> abandonedIds = new HashSet<>();
        for (CapturePointBlockEntity member : group) {
            if (member != be && member.pointId != be.pointId) {
                if (member.pointId > 0) abandonedIds.add(member.pointId);
                member.pointId = be.pointId;
                member.setChanged();
            }
        }
        if (!abandonedIds.isEmpty()) {
            TF1WorldData mergeWorldData = TF1WorldData.get(serverLevel);
            for (int abandoned : abandonedIds) {
                mergeWorldData.removePointOwner(abandoned);
            }
        }

        // 2. Collect all players standing on any block in the cluster (dedup by UUID)
        Set<UUID> seen = new HashSet<>();
        List<Player> allPlayers = new ArrayList<>();
        for (CapturePointBlockEntity member : group) {
            AABB area = new AABB(member.getBlockPos()).move(0, 1, 0);
            for (Player p : level.getEntitiesOfClass(Player.class, area)) {
                if (seen.add(p.getUUID())) allPlayers.add(p);
            }
        }

        // 3. Determine which single team is present (null if none or conflict)
        TF1Team foundTeam = null;
        boolean conflict = false;
        for (Player player : allPlayers) {
            TF1Team team = TeamManager.getTeam(player.getUUID());
            if (team == null) continue; // neutral players are ignored
            if (foundTeam == null) {
                foundTeam = team;
            } else if (foundTeam != team) {
                conflict = true;
                break;
            }
        }

        // 4. Recapture blocking: if the owning team has at least one player present,
        //    the enemy cannot start capturing.
        if (be.capturedBy != null && foundTeam != null && foundTeam != be.capturedBy) {
            boolean ownerPresent = allPlayers.stream()
                    .anyMatch(p -> TeamManager.getTeam(p.getUUID()) == be.capturedBy);
            if (ownerPresent) foundTeam = null;
        }

        // 5. Capture logic
        int captureDurationTicks =
                TF1WorldData.get(serverLevel).getCaptureTimeSeconds() * 20;

        TF1Team prevCapturedBy    = be.capturedBy;
        TF1Team prevCapturingTeam = be.capturingTeam;

        TF1Team newCapturedBy    = be.capturedBy;
        TF1Team newCapturingTeam = be.capturingTeam;
        int     newCaptureTicks  = be.captureTicks;
        boolean justCaptured     = false;

        if (foundTeam == null || conflict || foundTeam == be.capturedBy) {
            newCapturingTeam = null;
            newCaptureTicks  = 0;
        } else {
            if (foundTeam != be.capturingTeam) {
                newCapturingTeam = foundTeam;
                newCaptureTicks  = 0;
            }
            newCaptureTicks++;
            if (newCaptureTicks >= captureDurationTicks) {
                newCapturedBy    = foundTeam;
                newCapturingTeam = null;
                newCaptureTicks  = 0;
                justCaptured     = true;
            }
        }

        // 6. Send localized messages on notable events
        MinecraftServer server = serverLevel.getServer();

        if (justCaptured) {
            TF1Mod.LOGGER.info("[TF1] Point {} captured by {} (was: {})",
                    be.pointId, newCapturedBy, prevCapturedBy);
            // Persist new owner in global registry
            TF1WorldData.get(serverLevel).updatePointOwner(be.pointId, newCapturedBy);

            Component teamComp  = TF1Messages.teamComponent(newCapturedBy);
            Component pointComp = Component.literal(String.valueOf(be.pointId));

            if (prevCapturedBy == null) {
                // Neutral point captured — broadcast globally
                TF1Messages.sendGlobal(server,
                        Component.translatable("message.tf1.capture.neutral_captured",
                                teamComp, pointComp));
            } else {
                // Owned point captured — broadcast globally (old owner also sees it)
                Component oldTeamComp = TF1Messages.teamComponent(prevCapturedBy);
                TF1Messages.sendGlobal(server,
                        Component.translatable("message.tf1.capture.enemy_captured",
                                teamComp, pointComp, oldTeamComp));
            }
        }

        // "Enemy is trying to capture our point" — sent once when capture STARTS
        if (prevCapturingTeam == null && newCapturingTeam != null && be.capturedBy != null) {
            Component enemyComp = TF1Messages.teamComponent(newCapturingTeam);
            Component pointComp = Component.literal(String.valueOf(be.pointId));
            TF1Messages.sendToTeam(server, be.capturedBy,
                    Component.translatable("message.tf1.capture.enemy_capturing",
                            enemyComp, pointComp));
        }

        // 6b. Manage capture-progress boss bar (visible to all players on the server)
        if (newCapturingTeam != null) {
            be.ensureCaptureBar(newCapturingTeam);
            float progress = captureDurationTicks > 0
                    ? newCaptureTicks / (float) captureDurationTicks : 1.0f;
            int pct = Math.round(Math.min(1.0f, progress) * 100);
            be.captureBar.setProgress(Math.min(1.0f, Math.max(0.0f, progress)));
            be.captureBar.setName(buildBossBarTitle(be.pointId, newCapturedBy, newCapturingTeam, pct));
            // Add any player that joined after the bar was created
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (!be.captureBar.getPlayers().contains(sp)) be.captureBar.addPlayer(sp);
            }
        } else {
            be.destroyCaptureBar();
        }

        // 7. Propagate new capture state to all group members
        for (CapturePointBlockEntity member : group) {
            member.capturedBy    = newCapturedBy;
            member.capturingTeam = newCapturingTeam;
            member.captureTicks  = newCaptureTicks;
            if (justCaptured) {
                member.syncToClient();
            }
        }

        spawnParticles(level, pos, be);
    }

    // -------------------------------------------------------------------------
    // BFS cluster helpers
    // -------------------------------------------------------------------------

    /** 6-direction adjacency BFS — no diagonals. */
    private static List<CapturePointBlockEntity> findGroup(Level level, BlockPos origin) {
        List<CapturePointBlockEntity> group = new ArrayList<>();
        Set<BlockPos>   visited = new HashSet<>();
        Deque<BlockPos> queue   = new ArrayDeque<>();
        queue.add(origin);
        visited.add(origin);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (level.getBlockEntity(current) instanceof CapturePointBlockEntity cpbe) {
                group.add(cpbe);
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    if (!visited.contains(neighbor) &&
                            level.getBlockEntity(neighbor) instanceof CapturePointBlockEntity) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }
        return group;
    }

    /** Deterministic leader election: member with the smallest (X, Y, Z). */
    private static BlockPos getCanonical(List<CapturePointBlockEntity> group) {
        BlockPos min = group.get(0).getBlockPos();
        for (int i = 1; i < group.size(); i++) {
            BlockPos p = group.get(i).getBlockPos();
            if (p.getX() < min.getX()
                    || (p.getX() == min.getX() && p.getY() < min.getY())
                    || (p.getX() == min.getX() && p.getY() == min.getY()
                        && p.getZ() < min.getZ())) {
                min = p;
            }
        }
        return min;
    }

    // -------------------------------------------------------------------------
    // Particles
    // -------------------------------------------------------------------------

    private static void spawnParticles(Level level, BlockPos pos, CapturePointBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (level.getGameTime() % 4 != 0) return;

        DustParticleOptions dust;
        if (be.capturingTeam != null) {
            dust = NEUTRAL_DUST; // capture in progress — gray
        } else if (be.capturedBy != null) {
            dust = be.capturedBy.dustOptions(); // owned — team color
        } else {
            return; // uncaptured and idle — no particles
        }

        for (int dy = 1; dy <= 5; dy++) {
            serverLevel.sendParticles(dust,
                    pos.getX() + 0.5, pos.getY() + dy, pos.getZ() + 0.5,
                    2, 0.3, 0.1, 0.3, 0.0);
        }
    }

    // -------------------------------------------------------------------------
    // Boss bar helpers
    // -------------------------------------------------------------------------

    /** Creates a new bar if none exists or if the capturing team changed. */
    private void ensureCaptureBar(TF1Team team) {
        if (captureBar != null && lastBarTeam == team) return;
        destroyCaptureBar();
        captureBar = new ServerBossEvent(
                Component.literal(""),
                toBossBarColor(team),
                BossEvent.BossBarOverlay.PROGRESS
        );
        lastBarTeam = team;
    }

    /** Removes the bar from all players and clears the reference. */
    private void destroyCaptureBar() {
        if (captureBar != null) {
            captureBar.removeAllPlayers();
            captureBar = null;
            lastBarTeam = null;
        }
    }

    /**
     * Builds a colored boss bar title:
     * "Point N" in the owner's color (white if neutral) · "TEAM capturing XX%" in the capturing team's color.
     */
    private static Component buildBossBarTitle(int pointId, @Nullable TF1Team owner,
                                               TF1Team capturing, int pct) {
        ChatFormatting pointColor = (owner != null) ? owner.color : ChatFormatting.WHITE;
        MutableComponent pointPart = Component.translatable("ui.tf1.bossbar.point",
                        Component.literal(String.valueOf(pointId)))
                .withStyle(pointColor);
        MutableComponent capPart = Component.translatable("ui.tf1.bossbar.capturing",
                        Component.literal(capturing.name()).withStyle(capturing.color),
                        Component.literal(pct + "%").withStyle(capturing.color))
                .withStyle(capturing.color);
        return Component.empty()
                .append(pointPart)
                .append(Component.literal(" · ").withStyle(ChatFormatting.GRAY))
                .append(capPart);
    }

    private static BossEvent.BossBarColor toBossBarColor(TF1Team team) {
        return switch (team) {
            case RED   -> BossEvent.BossBarColor.RED;
            case BLUE  -> BossEvent.BossBarColor.BLUE;
            case GREEN -> BossEvent.BossBarColor.GREEN;
            case GOLD  -> BossEvent.BossBarColor.YELLOW;
        };
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getPointId() { return pointId; }

    /** Called on cluster split: updates this block's pointId and persists it. */
    public void setPointId(int id) {
        this.pointId = id;
        syncToClient();
    }

    @Nullable
    public TF1Team getCapturedBy() { return capturedBy; }

    public int getCaptureTicks() { return captureTicks; }

    /** Returns "Point N" or "Point N [TEAM]" for display in messages. */
    public String getDisplayName() {
        return capturedBy == null
                ? "Point " + pointId
                : "Point " + pointId + " [" + capturedBy.name() + "]";
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        destroyCaptureBar(); // clean up boss bar when block entity is unloaded/removed
    }

    // -------------------------------------------------------------------------
    // NBT persistence and client sync
    // -------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        writePersistentData(tag);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        writeSyncData(tag);
        return tag;
    }

    private void writePersistentData(CompoundTag tag) {
        tag.putInt(TAG_POINT_ID, pointId);
        if (capturedBy != null) {
            tag.putString(TAG_CAPTURED_BY, capturedBy.name());
        }
    }

    private void writeSyncData(CompoundTag tag) {
        writePersistentData(tag);
        if (capturingTeam != null) {
            tag.putString(TAG_CAPTURING_TEAM, capturingTeam.name());
        }
        tag.putInt(TAG_CAPTURE_TICKS, captureTicks);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        pointId      = tag.getInt(TAG_POINT_ID);
        capturedBy   = readTeam(tag, TAG_CAPTURED_BY);
        capturingTeam = readTeam(tag, TAG_CAPTURING_TEAM);
        captureTicks = tag.getInt(TAG_CAPTURE_TICKS);
    }

    @Nullable
    private static TF1Team readTeam(CompoundTag tag, String key) {
        if (!tag.contains(key)) return null;
        try {
            return TF1Team.valueOf(tag.getString(key));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void syncToClient() {
        setChanged();
        if (level == null) return;
        BlockState blockState = getBlockState();
        level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL);
    }
}
