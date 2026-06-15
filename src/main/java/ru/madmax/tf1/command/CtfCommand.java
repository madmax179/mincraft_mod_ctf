package ru.madmax.tf1.command;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import ru.madmax.tf1.data.MatchData;
import ru.madmax.tf1.team.TF1Team;
import ru.madmax.tf1.team.TeamManager;
import ru.madmax.tf1.util.ScoreboardHelper;
import ru.madmax.tf1.util.TF1Messages;

import java.util.*;
import java.util.stream.Collectors;

/** Static helpers shared between TF1Command (game sub-commands) and event handlers. */
public class CtfCommand {

    private CtfCommand() {}

    /** Admin = CTF admin UUID match OR server OP level 2. */
    public static boolean isAdmin(ServerPlayer player, MatchData matchData) {
        return matchData.isAdmin(player.getUUID()) || player.hasPermissions(2);
    }

    /**
     * Full match reset: clears match state, removes all player teams and helmets.
     * Admin UUID is NOT cleared.
     */
    public static void fullResetAndClearTeams(MinecraftServer server, MatchData matchData) {
        matchData.fullReset();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            TeamManager.removePlayer(p.getUUID());
            ScoreboardHelper.syncPlayerTeam(server, p, null);
            TF1Command.removeTeamHelmet(p);
        }
    }

    /**
     * Auto-assigns all unassigned online players to teams as evenly as possible.
     * Phase 1: fill each team to 2 players (smallest first).
     * Phase 2: distribute remaining players round-robin.
     */
    public static void autoAssignTeams(MinecraftServer server) {
        List<ServerPlayer> allPlayers = server.getPlayerList().getPlayers();
        TF1Team[] allTeams = TF1Team.values();

        Map<TF1Team, Integer> teamSizes = new EnumMap<>(TF1Team.class);
        for (TF1Team t : allTeams) teamSizes.put(t, 0);
        for (ServerPlayer p : allPlayers) {
            TF1Team t = TeamManager.getTeam(p.getUUID());
            if (t != null) teamSizes.merge(t, 1, Integer::sum);
        }

        List<ServerPlayer> unassigned = allPlayers.stream()
                .filter(p -> TeamManager.getTeam(p.getUUID()) == null)
                .collect(Collectors.toList());
        Collections.shuffle(unassigned);
        if (unassigned.isEmpty()) return;

        List<TF1Team> sortedTeams = Arrays.stream(allTeams)
                .sorted(Comparator.comparingInt(t -> teamSizes.getOrDefault(t, 0)))
                .collect(Collectors.toList());

        int idx = 0;

        // Phase 1: fill each team to 2
        for (TF1Team team : sortedTeams) {
            while (teamSizes.get(team) < 2 && idx < unassigned.size()) {
                assignPlayer(server, unassigned.get(idx++), team, teamSizes);
            }
        }

        // Phase 2: round-robin remainder
        int teamIdx = 0;
        while (idx < unassigned.size()) {
            TF1Team team = sortedTeams.get(teamIdx % sortedTeams.size());
            assignPlayer(server, unassigned.get(idx++), team, teamSizes);
            teamIdx++;
        }
    }

    private static void assignPlayer(MinecraftServer server, ServerPlayer player,
                                     TF1Team team, Map<TF1Team, Integer> teamSizes) {
        TeamManager.assignTeam(player.getUUID(), team);
        ScoreboardHelper.syncPlayerTeam(server, player, team);
        TF1Command.equipTeamHelmet(player, team);
        teamSizes.merge(team, 1, Integer::sum);
        player.sendSystemMessage(
                Component.translatable("message.tf1.team.auto_assigned",
                        TF1Messages.teamComponent(team)));
    }
}
