package ru.madmax.tf1.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import ru.madmax.tf1.game.GamePhase;
import ru.madmax.tf1.team.TF1Team;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Persistent config + transient match state.
 * Config (adminUUID, thresholds) survives server restarts.
 * Game state resets to IDLE on every server start (no half-finished game on restart).
 */
public class MatchData extends SavedData {

    private static final String DATA_NAME = "tf1_match_data";
    private static final int ROUND_END_PAUSE_TICKS = 5 * 20; // 5 s between rounds

    // ---- Persisted config ----
    @Nullable private UUID adminUUID = null;
    private int holdWinThresholdSeconds = 300; // 5 min hold → round win
    private int roundLimitSeconds       = 600; // 10 min round hard cap
    private int pregameSeconds          = 60;  // team-pick window
    private int roundsToWinMatch        = 2;   // first to N rounds wins match

    // ---- Transient game state (not saved; always reset on server start) ----
    private GamePhase phase               = GamePhase.IDLE;
    private int       roundNumber         = 0;
    private int       roundTimeTicks      = 0;
    private int       pregameCountdownTicks = 0;
    private int       roundEndPauseTicks  = 0;

    @Nullable private TF1Team lastRoundWinner = null;
    @Nullable private TF1Team matchWinner     = null;

    // Per-team accumulators (reset each round)
    private final Map<TF1Team, Long>    holdTimeTicks = new EnumMap<>(TF1Team.class);
    // Per-team match-level stats (reset on full reset)
    private final Map<TF1Team, Integer> roundWins     = new EnumMap<>(TF1Team.class);
    private final Map<TF1Team, Integer> teamKills     = new EnumMap<>(TF1Team.class);

    public MatchData() {
        initMaps();
    }

    private void initMaps() {
        for (TF1Team t : TF1Team.values()) {
            holdTimeTicks.put(t, 0L);
            roundWins.put(t, 0);
            teamKills.put(t, 0);
        }
    }

    // ---- Factory ----

    private static MatchData load(CompoundTag tag) {
        MatchData data = new MatchData();
        if (tag.contains("adminUUID"))
            data.adminUUID = UUID.fromString(tag.getString("adminUUID"));
        if (tag.contains("holdWinThresholdSeconds"))
            data.holdWinThresholdSeconds = tag.getInt("holdWinThresholdSeconds");
        if (tag.contains("roundLimitSeconds"))
            data.roundLimitSeconds = tag.getInt("roundLimitSeconds");
        if (tag.contains("pregameSeconds"))
            data.pregameSeconds = tag.getInt("pregameSeconds");
        if (tag.contains("roundsToWinMatch"))
            data.roundsToWinMatch = tag.getInt("roundsToWinMatch");
        return data;
    }

    public static MatchData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                MatchData::load, MatchData::new, DATA_NAME);
    }

    // ---- Admin ----

    @Nullable public UUID getAdminUUID() { return adminUUID; }
    public boolean hasAdmin() { return adminUUID != null; }
    public boolean isAdmin(UUID uuid) { return adminUUID != null && adminUUID.equals(uuid); }

    public void setAdmin(@Nullable UUID uuid) {
        this.adminUUID = uuid;
        setDirty();
    }

    // ---- Phase ----

    public GamePhase getPhase() { return phase; }
    public void setPhase(GamePhase p) { this.phase = p; }

    // ---- Pregame ----

    public int getPregameSeconds() { return pregameSeconds; }
    public void setPregameSeconds(int s) { pregameSeconds = Math.max(5, s); setDirty(); }

    public int getPregameCountdownTicks() { return pregameCountdownTicks; }
    public void decPregameCountdown() { if (pregameCountdownTicks > 0) pregameCountdownTicks--; }

    public void startPregame() {
        phase = GamePhase.PREGAME;
        pregameCountdownTicks = pregameSeconds * 20;
    }

    // ---- Round ----

    public int getRoundNumber() { return roundNumber; }

    /** Increments round counter, resets round-level state, sets phase ROUND_ACTIVE. */
    public void startRound() {
        roundNumber++;
        phase = GamePhase.ROUND_ACTIVE;
        for (TF1Team t : TF1Team.values()) holdTimeTicks.put(t, 0L);
        roundTimeTicks = 0;
        lastRoundWinner = null;
    }

    public int getRoundTimeTicks() { return roundTimeTicks; }
    public void incRoundTimeTicks() { roundTimeTicks++; }

    public int getRoundLimitSeconds() { return roundLimitSeconds; }
    public void setRoundLimitSeconds(int s) { roundLimitSeconds = Math.max(60, s); setDirty(); }

    public int getHoldWinThresholdSeconds() { return holdWinThresholdSeconds; }
    public void setHoldWinThresholdSeconds(int s) { holdWinThresholdSeconds = Math.max(30, s); setDirty(); }

    public int getRoundsToWinMatch() { return roundsToWinMatch; }
    public void setRoundsToWinMatch(int n) { roundsToWinMatch = Math.max(1, n); setDirty(); }

    // ---- Hold time ----

    public long getHoldTimeTicks(TF1Team team) {
        return holdTimeTicks.getOrDefault(team, 0L);
    }

    public void addHoldTicks(TF1Team team, int ticks) {
        holdTimeTicks.merge(team, (long) ticks, Long::sum);
    }

    // ---- Round end / win ----

    public int getRoundEndPauseTicks() { return roundEndPauseTicks; }
    public void decRoundEndPause() { if (roundEndPauseTicks > 0) roundEndPauseTicks--; }

    /**
     * Transitions to ROUND_END phase, records the winner (null = no winner / tie at zero),
     * and awards a round win point if winner != null.
     */
    public void beginRoundEnd(@Nullable TF1Team winner) {
        phase = GamePhase.ROUND_END;
        roundEndPauseTicks = ROUND_END_PAUSE_TICKS;
        lastRoundWinner = winner;
        if (winner != null) {
            roundWins.merge(winner, 1, Integer::sum);
            setDirty();
        }
    }

    @Nullable public TF1Team getLastRoundWinner() { return lastRoundWinner; }

    public int getRoundWins(TF1Team team) {
        return roundWins.getOrDefault(team, 0);
    }

    // ---- Match winner ----

    /** Returns the first team that has reached the rounds-to-win threshold, or null. */
    @Nullable
    public TF1Team checkMatchWinner() {
        for (TF1Team t : TF1Team.values()) {
            if (roundWins.getOrDefault(t, 0) >= roundsToWinMatch) return t;
        }
        return null;
    }

    public void setMatchWinner(@Nullable TF1Team team) { this.matchWinner = team; }
    @Nullable public TF1Team getMatchWinner() { return matchWinner; }

    // ---- Kill tracking ----

    public int getKills(TF1Team team) { return teamKills.getOrDefault(team, 0); }
    public void addKill(TF1Team team) { teamKills.merge(team, 1, Integer::sum); }

    // ---- Reset helpers ----

    /** Stops the current game/round; preserves match-level scores (wins, kills). */
    public void stopGame() {
        phase = GamePhase.IDLE;
        roundTimeTicks = 0;
        pregameCountdownTicks = 0;
        roundEndPauseTicks = 0;
        lastRoundWinner = null;
        for (TF1Team t : TF1Team.values()) holdTimeTicks.put(t, 0L);
    }

    /** Full reset: clears all match state and scores. Admin UUID is preserved. */
    public void fullReset() {
        phase = GamePhase.IDLE;
        roundNumber = 0;
        roundTimeTicks = 0;
        pregameCountdownTicks = 0;
        roundEndPauseTicks = 0;
        lastRoundWinner = null;
        matchWinner = null;
        for (TF1Team t : TF1Team.values()) {
            holdTimeTicks.put(t, 0L);
            roundWins.put(t, 0);
            teamKills.put(t, 0);
        }
    }

    // ---- Serialization ----

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (adminUUID != null) tag.putString("adminUUID", adminUUID.toString());
        tag.putInt("holdWinThresholdSeconds", holdWinThresholdSeconds);
        tag.putInt("roundLimitSeconds", roundLimitSeconds);
        tag.putInt("pregameSeconds", pregameSeconds);
        tag.putInt("roundsToWinMatch", roundsToWinMatch);
        return tag;
    }
}
