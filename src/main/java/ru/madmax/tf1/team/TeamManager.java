package ru.madmax.tf1.team;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MVP team storage — in-memory only.
 * Limitation: data is lost on server restart.
 * Future: migrate to WorldSavedData for persistence.
 */
public class TeamManager {

    private static final Map<UUID, TF1Team> playerTeams = new HashMap<>();

    public static void assignTeam(UUID playerId, TF1Team team) {
        playerTeams.put(playerId, team);
    }

    @Nullable
    public static TF1Team getTeam(UUID playerId) {
        return playerTeams.get(playerId);
    }

    public static void removePlayer(UUID playerId) {
        playerTeams.remove(playerId);
    }

    /** Removes the player from their team, making them Neutral. Alias for {@link #removePlayer}. */
    public static void leaveTeam(UUID playerId) {
        removePlayer(playerId);
    }
}
