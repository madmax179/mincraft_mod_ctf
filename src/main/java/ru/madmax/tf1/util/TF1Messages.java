package ru.madmax.tf1.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ru.madmax.tf1.data.TF1WorldData;
import ru.madmax.tf1.team.TF1Team;
import ru.madmax.tf1.team.TeamManager;

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
    // Status / Tab header helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the rich-text header shown in the Tab overlay.
     * Format (3 lines):
     *   === TF1 ===
     *   [RED] 2  [BLUE] 1  [GREEN] 0  [GOLD] 0  Neutral: 1
     *   Points: 1=[RED]  2=[BLUE]  3=Neutral
     */
    public static Component buildTabHeader(MinecraftServer server) {
        // Count players per team
        Map<TF1Team, List<String>> teamPlayers = new LinkedHashMap<>();
        for (TF1Team t : TF1Team.values()) teamPlayers.put(t, new ArrayList<>());
        List<String> neutralPlayers = new ArrayList<>();

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            TF1Team t = TeamManager.getTeam(sp.getUUID());
            if (t == null) neutralPlayers.add(sp.getName().getString());
            else teamPlayers.get(t).add(sp.getName().getString());
        }

        // Line 1: title
        MutableComponent title = Component.literal("=== TF1 ===").withStyle(ChatFormatting.GOLD);

        // Line 2: team counts
        MutableComponent teamLine = Component.empty();
        for (TF1Team t : TF1Team.values()) {
            int count = teamPlayers.get(t).size();
            teamLine.append(Component.literal("[").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(t.name()).withStyle(t.color))
                    .append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(count)).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  ").withStyle(ChatFormatting.RESET));
        }
        teamLine.append(Component.literal("Neutral: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(neutralPlayers.size())).withStyle(ChatFormatting.WHITE));

        // Line 3: points
        SortedMap<Integer, TF1Team> owners = TF1WorldData.get(server).getPointOwners();
        MutableComponent pointLine;
        if (owners.isEmpty()) {
            pointLine = Component.literal("Points: —").withStyle(ChatFormatting.GRAY);
        } else {
            pointLine = Component.literal("Points: ").withStyle(ChatFormatting.WHITE);
            boolean first = true;
            for (Map.Entry<Integer, TF1Team> e : owners.entrySet()) {
                if (!first) pointLine.append(Component.literal("  ").withStyle(ChatFormatting.RESET));
                first = false;
                ChatFormatting col = e.getValue() != null ? e.getValue().color : ChatFormatting.GRAY;
                String ownerName = e.getValue() != null ? e.getValue().name() : "Neutral";
                pointLine.append(Component.literal(e.getKey() + "=").withStyle(ChatFormatting.WHITE))
                         .append(Component.literal("[" + ownerName + "]").withStyle(col));
            }
        }

        return Component.empty()
                .append(title)
                .append(Component.literal("\n"))
                .append(teamLine)
                .append(Component.literal("\n"))
                .append(pointLine);
    }

    /**
     * Builds a list of lines for the {@code /tf1 status} command output.
     * Works in both in-game chat and server console.
     */
    public static List<Component> buildStatusLines(MinecraftServer server) {
        List<Component> lines = new ArrayList<>();

        // Header
        lines.add(Component.literal("--- TF1 Status ---").withStyle(ChatFormatting.GOLD));

        // Teams
        for (TF1Team t : TF1Team.values()) {
            List<String> names = new ArrayList<>();
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (TeamManager.getTeam(sp.getUUID()) == t)
                    names.add(sp.getName().getString());
            }
            MutableComponent line = Component.literal("[").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(t.name()).withStyle(t.color))
                    .append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(names.isEmpty() ? "(no players)" : String.join(", ", names))
                            .withStyle(ChatFormatting.WHITE));
            lines.add(line);
        }

        // Neutral
        List<String> neutral = new ArrayList<>();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (TeamManager.getTeam(sp.getUUID()) == null)
                neutral.add(sp.getName().getString());
        }
        lines.add(Component.literal("Neutral: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(neutral.isEmpty() ? "(none)" : String.join(", ", neutral))
                        .withStyle(ChatFormatting.WHITE)));

        // Points
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
