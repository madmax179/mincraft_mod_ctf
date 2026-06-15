package ru.madmax.tf1.util;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import ru.madmax.tf1.team.TF1Team;

import javax.annotation.Nullable;
import java.util.ArrayList;

/**
 * Manages vanilla Scoreboard team membership to give players a colored name prefix
 * (visible in chat, above head, and in the tab list) without client-side mods.
 *
 * One scoreboard team is created per TF1Team with the name pattern "tf1_<teamname>".
 * TeamManager remains the authoritative in-memory source; scoreboard is a visual mirror.
 *
 * On server start all tf1_* memberships are cleared so that stale scoreboard state
 * (persisted in scoreboard.dat) does not diverge from the in-memory TeamManager.
 */
public class ScoreboardHelper {

    private ScoreboardHelper() {}

    private static String sbTeamName(TF1Team team) {
        return "tf1_" + team.name().toLowerCase();
    }

    /**
     * Creates the tf1_* scoreboard teams if they do not exist and sets their
     * display color and prefix. Safe to call multiple times.
     */
    public static void ensureTeams(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        for (TF1Team team : TF1Team.values()) {
            String name = sbTeamName(team);
            PlayerTeam sbTeam = scoreboard.getPlayerTeam(name);
            if (sbTeam == null) {
                sbTeam = scoreboard.addPlayerTeam(name);
            }
            sbTeam.setColor(team.color);
            sbTeam.setPlayerPrefix(
                    Component.literal("[" + team.name() + "] ").withStyle(team.color));
        }
    }

    /**
     * Removes every player from all tf1_* scoreboard teams.
     * Called on server start to prevent TeamManager/scoreboard divergence after restart.
     */
    public static void clearAllTF1Memberships(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        for (TF1Team team : TF1Team.values()) {
            PlayerTeam sbTeam = scoreboard.getPlayerTeam(sbTeamName(team));
            if (sbTeam != null) {
                new ArrayList<>(sbTeam.getPlayers())
                        .forEach(name -> scoreboard.removePlayerFromTeam(name, sbTeam));
            }
        }
    }

    /**
     * Syncs a player to the scoreboard team matching {@code team},
     * or removes them from all tf1_* teams if {@code team} is {@code null}.
     *
     * Uses getPlayersTeam() to find the player's current team before removing —
     * removePlayerFromTeam() throws IllegalStateException if the player is not
     * in the specified team, so we must check first.
     */
    public static void syncPlayerTeam(MinecraftServer server,
                                      ServerPlayer player,
                                      @Nullable TF1Team team) {
        Scoreboard scoreboard = server.getScoreboard();
        String scoreName = player.getScoreboardName();

        // Only remove if the player is currently in a tf1_* team
        PlayerTeam currentTeam = scoreboard.getPlayersTeam(scoreName);
        if (currentTeam != null && currentTeam.getName().startsWith("tf1_")) {
            scoreboard.removePlayerFromTeam(scoreName, currentTeam);
        }

        if (team != null) {
            PlayerTeam sbTeam = scoreboard.getPlayerTeam(sbTeamName(team));
            if (sbTeam != null) {
                scoreboard.addPlayerToTeam(scoreName, sbTeam);
            }
        }
    }
}
