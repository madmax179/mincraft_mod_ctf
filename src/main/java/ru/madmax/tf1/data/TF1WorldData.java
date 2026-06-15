package ru.madmax.tf1.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import ru.madmax.tf1.team.TF1Team;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Global world-level persistent settings for TF1.
 * Stored in the overworld's data storage so it survives server restarts.
 */
public class TF1WorldData extends SavedData {

    private static final String DATA_NAME = "tf1_world_data";
    private static final int DEFAULT_CAPTURE_TIME_SECONDS = 10;

    private int nextPointId = 1;
    private int captureTimeSeconds = DEFAULT_CAPTURE_TIME_SECONDS;
    private boolean friendlyFireEnabled = false;

    /** pointId → owning team (null = neutral/unowned). Sorted so status output is ordered. */
    private final TreeMap<Integer, TF1Team> pointOwners = new TreeMap<>();

    public TF1WorldData() {}

    // ---- Factory ----

    private static TF1WorldData load(CompoundTag tag) {
        TF1WorldData data = new TF1WorldData();
        data.nextPointId = tag.getInt("nextPointId");
        if (data.nextPointId < 1) data.nextPointId = 1;
        if (tag.contains("captureTimeSeconds")) {
            data.captureTimeSeconds = tag.getInt("captureTimeSeconds");
        }
        data.friendlyFireEnabled = tag.getBoolean("friendlyFireEnabled");

        if (tag.contains("pointOwners")) {
            CompoundTag ownersTag = tag.getCompound("pointOwners");
            for (String key : ownersTag.getAllKeys()) {
                try {
                    int id = Integer.parseInt(key);
                    String val = ownersTag.getString(key);
                    TF1Team team = val.equals("NEUTRAL") ? null : TF1Team.valueOf(val);
                    data.pointOwners.put(id, team);
                } catch (Exception ignored) {}
            }
        }
        return data;
    }

    public static TF1WorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                TF1WorldData::load,
                TF1WorldData::new,
                DATA_NAME
        );
    }

    public static TF1WorldData get(ServerLevel level) {
        return get(level.getServer());
    }

    // ---- Point ID ----

    /** Allocates the next unique point ID and persists the counter. */
    public int allocatePointId() {
        int id = nextPointId++;
        setDirty();
        return id;
    }

    // ---- Point owners ----

    /** Records (or updates) which team owns a given point. {@code null} = neutral. */
    public void updatePointOwner(int pointId, @Nullable TF1Team team) {
        pointOwners.put(pointId, team);
        setDirty();
    }

    /** Removes a point from the registry (last block destroyed, or cluster merged into another). */
    public void removePointOwner(int pointId) {
        if (pointOwners.remove(pointId) != null) {
            setDirty();
        }
    }

    /** Returns an unmodifiable sorted view of all known point IDs and their owners. */
    public SortedMap<Integer, TF1Team> getPointOwners() {
        return Collections.unmodifiableSortedMap(pointOwners);
    }

    // ---- Capture time ----

    public int getCaptureTimeSeconds() {
        return captureTimeSeconds;
    }

    public void setCaptureTimeSeconds(int seconds) {
        this.captureTimeSeconds = Math.max(1, seconds);
        setDirty();
    }

    // ---- Friendly fire ----

    public boolean isFriendlyFireEnabled() {
        return friendlyFireEnabled;
    }

    public void setFriendlyFireEnabled(boolean enabled) {
        this.friendlyFireEnabled = enabled;
        setDirty();
    }

    // ---- Serialization ----

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("nextPointId", nextPointId);
        tag.putInt("captureTimeSeconds", captureTimeSeconds);
        tag.putBoolean("friendlyFireEnabled", friendlyFireEnabled);

        CompoundTag ownersTag = new CompoundTag();
        for (java.util.Map.Entry<Integer, TF1Team> e : pointOwners.entrySet()) {
            ownersTag.putString(
                    String.valueOf(e.getKey()),
                    e.getValue() != null ? e.getValue().name() : "NEUTRAL"
            );
        }
        tag.put("pointOwners", ownersTag);
        return tag;
    }
}
