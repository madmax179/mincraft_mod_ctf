package ru.madmax.tf1.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ru.madmax.tf1.data.MatchData;
import ru.madmax.tf1.data.TF1WorldData;
import ru.madmax.tf1.game.GamePhase;
import ru.madmax.tf1.team.TF1Team;
import ru.madmax.tf1.team.TeamManager;

import javax.annotation.Nullable;
import java.util.*;

/** Helpers to send localized messages to all players or to a specific team. */
public class TF1Messages {

    private TF1Messages() {}

    /** Broadcasts a message to every online player. */
    public static void sendGlobal(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    /** Sends a message only to players whose current TF1 team matches {@code team}. */
    public static void sendToTeam(MinecraftServer server, TF1Team team, Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (TeamManager.getTeam(player.getUUID()) == team) {
                player.sendSystemMessage(message);
            }
        }
    }

    /** Returns a team-colored {@link Component} with the team name, e.g. "BLUE" in blue. */
    public static Component teamComponent(TF1Team team) {
        return Component.literal(team.name()).withStyle(team.color);
    }

    // -------------------------------------------------------------------------
    // Formatting utilities
    // -------------------------------------------------------------------------

    /** Converts a tick count to "M:SS" display string. */
    public static String formatTicks(long ticks) {
        long total = ticks / 20;
        return String.format("%d:%02d", total / 60, total % 60);
    }

    /** Converts whole seconds to "M:SS" display string. */
    public static String formatSeconds(long seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    // -------------------------------------------------------------------------
    // Tab header (updated each 5 s via TF1EventHandlers)
    // -------------------------------------------------------------------------

    /**
     * Builds the rich-text header shown in the Tab overlay.
     * Shows phase/timer, round wins + kills per team, hold times, zone ownership, player counts.
     */
    public static Component buildTabHeader(MinecraftServer server, MatchData matchData) {
        GamePhase phase = matchData.getPhase();

        // --- Line 1: title + phase ---
        MutableComponent title = Component.literal("=== CTF ===").withStyle(ChatFormatting.GOLD);

        // --- Line 2: round info ---
        MutableComponent roundLine = buildRoundLine(matchData, phase);

        // --- Line 3: per-team scores (wins | kills) ---
        MutableComponent scoreLine = Component.literal("Score: ").withStyle(ChatFormatting.WHITE);
        for (TF1Team t : TF1Team.values()) {
            scoreLine.append(Component.literal("[").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(t.name()).withStyle(t.color))
                    .append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("W:" + matchData.getRoundWins(t)
                            + " K:" + matchData.getKills(t)).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  "));
        }

        // --- Line 4: hold times (shown during/after rounds) ---
        MutableComponent holdLine = Component.literal("Hold: ").withStyle(ChatFormatting.WHITE);
        String threshold = formatSeconds(matchData.getHoldWinThresholdSeconds());
        for (TF1Team t : TF1Team.values()) {
            String hold = formatTicks(matchData.getHoldTimeTicks(t));
            holdLine.append(Component.literal(t.name() + " ").withStyle(t.color))
                    .append(Component.literal(hold + "/" + threshold + "  ")
                            .withStyle(ChatFormatting.WHITE));
        }

        // --- Line 5: zone ownership ---
        SortedMap<Integer, TF1Team> owners = TF1WorldData.get(server).getPointOwners();
        MutableComponent pointLine;
        if (owners.isEmpty()) {
            pointLine = Component.literal("Points: —").withStyle(ChatFormatting.GRAY);
        } else {
            pointLine = Component.literal("Points: ").withStyle(ChatFormatting.WHITE);
            boolean first = true;
            for (Map.Entry<Integer, TF1Team> e : owners.entrySet()) {
                if (!first) pointLine.append("  ");
                first = false;
                ChatFormatting col = e.getValue() != null ? e.getValue().color : ChatFormatting.GRAY;
                String ownerName = e.getValue() != null ? e.getValue().name() : "Neutral";
                pointLine.append(Component.literal(e.getKey() + "=").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal("[" + ownerName + "]").withStyle(col));
            }
        }

        // --- Line 6: player counts ---
        Map<TF1Team, Integer> teamCounts = new EnumMap<>(TF1Team.class);
        for (TF1Team t : TF1Team.values()) teamCounts.put(t, 0);
        int neutralCount = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            TF1Team t = TeamManager.getTeam(sp.getUUID());
            if (t == null) neutralCount++;
            else teamCounts.merge(t, 1, Integer::sum);
        }
        MutableComponent playerLine = Component.literal("Players: ").withStyle(ChatFormatting.WHITE);
        for (TF1Team t : TF1Team.values()) {
            playerLine.append(Component.literal("[").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(t.name()).withStyle(t.color))
                    .append(Component.literal("] " + teamCounts.get(t) + "  ").withStyle(ChatFormatting.WHITE));
        }
        playerLine.append(Component.literal("Neutral: " + neutralCount).withStyle(ChatFormatting.GRAY));

        return Component.empty()
                .append(title).append("\n")
                .append(roundLine).append("\n")
                .append(scoreLine).append("\n")
                .append(holdLine).append("\n")
                .append(pointLine).append("\n")
                .append(playerLine);
    }

    private static MutableComponent buildRoundLine(MatchData matchData, GamePhase phase) {
        return switch (phase) {
            case IDLE -> Component.literal("Waiting for game...").withStyle(ChatFormatting.GRAY);
            case PREGAME -> {
                int secs = matchData.getPregameCountdownTicks() / 20;
                yield Component.literal("PREGAME ")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("— team pick — ")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(secs + "s")
                                .withStyle(ChatFormatting.GREEN));
            }
            case ROUND_ACTIVE -> {
                String timer = formatTicks(matchData.getRoundTimeTicks());
                String limit = formatSeconds(matchData.getRoundLimitSeconds());
                yield Component.literal("Round " + matchData.getRoundNumber() + " ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("[" + timer + " / " + limit + "]")
                                .withStyle(ChatFormatting.WHITE));
            }
            case ROUND_END -> {
                TF1Team winner = matchData.getLastRoundWinner();
                MutableComponent line = Component.literal("Round " + matchData.getRoundNumber() + " OVER ")
                        .withStyle(ChatFormatting.GOLD);
                if (winner != null) {
                    line.append(Component.literal("Winner: ").withStyle(ChatFormatting.WHITE))
                            .append(teamComponent(winner));
                } else {
                    line.append(Component.literal("No winner").withStyle(ChatFormatting.GRAY));
                }
                yield line;
            }
            case MATCH_END -> {
                TF1Team winner = matchData.getMatchWinner();
                MutableComponent line = Component.literal("MATCH OVER! ").withStyle(ChatFormatting.GOLD);
                if (winner != null) {
                    line.append(teamComponent(winner))
                            .append(Component.literal(" wins the match!").withStyle(ChatFormatting.WHITE));
                }
                yield line;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Actionbar HUD (updated each 1 s via TF1EventHandlers)
    // -------------------------------------------------------------------------

    /** Builds a compact one-line actionbar message suited to the current game phase. */
    public static Component buildHud(MinecraftServer server, MatchData matchData) {
        return switch (matchData.getPhase()) {
            case PREGAME -> buildPregameHud(matchData);
            case ROUND_ACTIVE -> buildRoundActiveHud(matchData);
            case ROUND_END -> buildRoundEndHud(matchData);
            case MATCH_END -> buildMatchEndHud(matchData);
            default -> Component.empty();
        };
    }

    private static Component buildPregameHud(MatchData matchData) {
        int secs = matchData.getPregameCountdownTicks() / 20;
        return Component.literal("PREGAME ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal("· Choose: ").withStyle(ChatFormatting.RESET, ChatFormatting.GRAY))
                .append(Component.literal("/tf1 join <red|blue|green|gold>").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  Starting in: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(secs + "s").withStyle(ChatFormatting.GREEN));
    }

    private static Component buildRoundActiveHud(MatchData matchData) {
        String timer = formatTicks(matchData.getRoundTimeTicks());
        String limit = formatSeconds(matchData.getRoundLimitSeconds());
        String threshold = formatSeconds(matchData.getHoldWinThresholdSeconds());

        MutableComponent line = Component.literal(
                        "Round " + matchData.getRoundNumber() + " ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("[" + timer + "/" + limit + "]  ")
                        .withStyle(ChatFormatting.WHITE));

        for (TF1Team t : TF1Team.values()) {
            String hold = formatTicks(matchData.getHoldTimeTicks(t));
            line.append(Component.literal(t.name().charAt(0) + ":")
                            .withStyle(t.color))
                    .append(Component.literal(hold + "/" + threshold + "  ")
                            .withStyle(ChatFormatting.WHITE));
        }
        return line;
    }

    private static Component buildRoundEndHud(MatchData matchData) {
        TF1Team winner = matchData.getLastRoundWinner();
        int pauseSecs = matchData.getRoundEndPauseTicks() / 20;
        MutableComponent line = Component.literal("Round over! ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (winner != null) {
            line.append(teamComponent(winner))
                    .append(Component.literal(" wins! ").withStyle(ChatFormatting.RESET, ChatFormatting.WHITE));
        } else {
            line.append(Component.literal("No winner! ").withStyle(ChatFormatting.RESET, ChatFormatting.GRAY));
        }
        line.append(Component.literal("Next round in " + pauseSecs + "s")
                .withStyle(ChatFormatting.GRAY));
        return line;
    }

    private static Component buildMatchEndHud(MatchData matchData) {
        TF1Team winner = matchData.getMatchWinner();
        MutableComponent line = Component.literal("MATCH OVER! ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (winner != null) {
            line.append(teamComponent(winner))
                    .append(Component.literal(" wins the match!").withStyle(ChatFormatting.RESET, ChatFormatting.WHITE));
        }
        line.append(Component.literal("  /ctf-start for new game").withStyle(ChatFormatting.GRAY));
        return line;
    }

    // -------------------------------------------------------------------------
    // /tf1 status output
    // -------------------------------------------------------------------------

    public static List<Component> buildStatusLines(MinecraftServer server) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("--- TF1 Status ---").withStyle(ChatFormatting.GOLD));

        for (TF1Team t : TF1Team.values()) {
            List<String> names = new ArrayList<>();
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (TeamManager.getTeam(sp.getUUID()) == t) names.add(sp.getName().getString());
            }
            lines.add(Component.literal("[").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(t.name()).withStyle(t.color))
                    .append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(names.isEmpty() ? "(no players)" : String.join(", ", names))
                            .withStyle(ChatFormatting.WHITE)));
        }

        List<String> neutral = new ArrayList<>();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (TeamManager.getTeam(sp.getUUID()) == null) neutral.add(sp.getName().getString());
        }
        lines.add(Component.literal("Neutral: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(neutral.isEmpty() ? "(none)" : String.join(", ", neutral))
                        .withStyle(ChatFormatting.WHITE)));

        lines.add(Component.literal("Points:").withStyle(ChatFormatting.YELLOW));
        SortedMap<Integer, TF1Team> owners = TF1WorldData.get(server).getPointOwners();
        if (owners.isEmpty()) {
            lines.add(Component.literal("  (no capture points registered)").withStyle(ChatFormatting.GRAY));
        } else {
            for (Map.Entry<Integer, TF1Team> e : owners.entrySet()) {
                ChatFormatting col = e.getValue() != null ? e.getValue().color : ChatFormatting.GRAY;
                String ownerName = e.getValue() != null ? e.getValue().name() : "Neutral";
                lines.add(Component.literal("  Point " + e.getKey() + ": ").withStyle(ChatFormatting.WHITE)
                        .append(Component.literal("[" + ownerName + "]").withStyle(col)));
            }
        }
        return lines;
    }
}

