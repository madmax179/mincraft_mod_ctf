package ru.madmax.tf1.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ru.madmax.tf1.data.MatchData;
import ru.madmax.tf1.data.TF1WorldData;
import ru.madmax.tf1.game.GamePhase;
import ru.madmax.tf1.team.TF1Team;
import ru.madmax.tf1.team.TeamManager;
import ru.madmax.tf1.util.ScoreboardHelper;
import ru.madmax.tf1.util.TF1Messages;

import java.util.*;
import java.util.stream.Collectors;

public class CtfCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // /ctf-start — any player; starts pregame for a fresh match
        dispatcher.register(
            Commands.literal("ctf-start")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    MinecraftServer server = ctx.getSource().getServer();
                    MatchData matchData = MatchData.get(server);
                    GamePhase phase = matchData.getPhase();

                    if (phase == GamePhase.PREGAME) {
                        ctx.getSource().sendFailure(
                                Component.translatable("command.ctf.error.pregame_active"));
                        return 0;
                    }
                    if (phase == GamePhase.ROUND_ACTIVE || phase == GamePhase.ROUND_END) {
                        ctx.getSource().sendFailure(
                                Component.translatable("command.ctf.error.game_in_progress"));
                        return 0;
                    }

                    // IDLE or MATCH_END → full reset then pregame
                    fullResetAndClearTeams(server, matchData);
                    matchData.startPregame();

                    TF1Messages.sendGlobal(server,
                            Component.translatable("command.ctf.start.announced",
                                    Component.literal(player.getName().getString())
                                            .withStyle(ChatFormatting.YELLOW),
                                    Component.literal(String.valueOf(matchData.getPregameSeconds()))
                                            .withStyle(ChatFormatting.GREEN)));
                    return 1;
                })
        );

        // /ctf-stop — admin only; stops game, preserves match scores
        dispatcher.register(
            Commands.literal("ctf-stop")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    MatchData matchData = MatchData.get(server);
                    ServerPlayer player = ctx.getSource().getPlayerOrException();

                    if (!isAdmin(player, matchData)) {
                        ctx.getSource().sendFailure(
                                Component.translatable("command.ctf.error.not_admin"));
                        return 0;
                    }
                    if (matchData.getPhase() == GamePhase.IDLE) {
                        ctx.getSource().sendFailure(
                                Component.translatable("command.ctf.error.no_game"));
                        return 0;
                    }

                    matchData.stopGame();
                    TF1Messages.sendGlobal(server,
                            Component.translatable("command.ctf.stop.stopped")
                                    .withStyle(ChatFormatting.RED));
                    return 1;
                })
        );

        // /ctf-restart — admin only; full reset
        dispatcher.register(
            Commands.literal("ctf-restart")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    MatchData matchData = MatchData.get(server);
                    ServerPlayer player = ctx.getSource().getPlayerOrException();

                    if (!isAdmin(player, matchData)) {
                        ctx.getSource().sendFailure(
                                Component.translatable("command.ctf.error.not_admin"));
                        return 0;
                    }

                    fullResetAndClearTeams(server, matchData);
                    TF1Messages.sendGlobal(server,
                            Component.translatable("command.ctf.restart.restarted")
                                    .withStyle(ChatFormatting.GOLD));
                    return 1;
                })
        );

        // /ctf-admin claim | /ctf-admin set <player>
        dispatcher.register(
            Commands.literal("ctf-admin")
                .then(Commands.literal("claim")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        MatchData matchData = MatchData.get(ctx.getSource().getServer());

                        if (matchData.hasAdmin()) {
                            ctx.getSource().sendFailure(
                                    Component.translatable("command.ctf.admin.already_set"));
                            return 0;
                        }

                        matchData.setAdmin(player.getUUID());
                        ctx.getSource().sendSuccess(
                                () -> Component.translatable("command.ctf.admin.claimed",
                                        Component.literal(player.getName().getString())
                                                .withStyle(ChatFormatting.GOLD)),
                                false);
                        return 1;
                    })
                )
                .then(Commands.literal("set")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer sender = ctx.getSource().getPlayerOrException();
                            MatchData matchData = MatchData.get(ctx.getSource().getServer());

                            if (!isAdmin(sender, matchData)) {
                                ctx.getSource().sendFailure(
                                        Component.translatable("command.ctf.error.not_admin"));
                                return 0;
                            }

                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                            matchData.setAdmin(target.getUUID());
                            TF1Messages.sendGlobal(ctx.getSource().getServer(),
                                    Component.translatable("command.ctf.admin.transferred",
                                            Component.literal(target.getName().getString())
                                                    .withStyle(ChatFormatting.GOLD)));
                            return 1;
                        })
                    )
                )
        );

        // /ctf-capturetime <seconds> — admin only
        dispatcher.register(
            Commands.literal("ctf-capturetime")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        MatchData matchData = MatchData.get(ctx.getSource().getServer());

                        if (!isAdmin(player, matchData)) {
                            ctx.getSource().sendFailure(
                                    Component.translatable("command.ctf.error.not_admin"));
                            return 0;
                        }

                        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                        TF1WorldData.get(ctx.getSource().getServer()).setCaptureTimeSeconds(seconds);
                        ctx.getSource().sendSuccess(
                                () -> Component.translatable("command.tf1.capturetime.set", seconds),
                                true);
                        return 1;
                    })
                )
        );
    }

    // ---- Helpers ----

    /** Admin = player is CTF admin OR has server OP level 2. */
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
     * Phase 1: fill each team to 2 players (sorted by current size, ascending).
     * Phase 2: distribute remaining players round-robin.
     */
    public static void autoAssignTeams(MinecraftServer server) {
        List<ServerPlayer> allPlayers = server.getPlayerList().getPlayers();
        TF1Team[] allTeams = TF1Team.values();

        // Current team sizes
        Map<TF1Team, Integer> teamSizes = new EnumMap<>(TF1Team.class);
        for (TF1Team t : allTeams) teamSizes.put(t, 0);
        for (ServerPlayer p : allPlayers) {
            TF1Team t = TeamManager.getTeam(p.getUUID());
            if (t != null) teamSizes.merge(t, 1, Integer::sum);
        }

        // Shuffle unassigned players for fairness
        List<ServerPlayer> unassigned = allPlayers.stream()
                .filter(p -> TeamManager.getTeam(p.getUUID()) == null)
                .collect(Collectors.toList());
        Collections.shuffle(unassigned);
        if (unassigned.isEmpty()) return;

        // Sort teams ascending by size so smallest are filled first
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

        // Phase 2: round-robin for any remaining
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
