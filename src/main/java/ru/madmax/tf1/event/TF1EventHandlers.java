package ru.madmax.tf1.event;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ru.madmax.tf1.block.CapturePointBlock;
import ru.madmax.tf1.command.CtfCommand;
import ru.madmax.tf1.data.MatchData;
import ru.madmax.tf1.data.TF1WorldData;
import ru.madmax.tf1.game.GamePhase;
import ru.madmax.tf1.team.TF1Team;
import ru.madmax.tf1.team.TeamManager;
import ru.madmax.tf1.util.ScoreboardHelper;
import ru.madmax.tf1.util.TF1Messages;

import javax.annotation.Nullable;
import java.util.*;

public class TF1EventHandlers {

    private static final int TAB_UPDATE_INTERVAL = 100; // 5 s
    private static final int HUD_UPDATE_INTERVAL = 20;  // 1 s

    private int tabUpdateCounter = 0;
    private int hudUpdateCounter = 0;

    // -------------------------------------------------------------------------
    // Server startup
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ScoreboardHelper.ensureTeams(server);
        ScoreboardHelper.clearAllTF1Memberships(server);
    }

    // -------------------------------------------------------------------------
    // Player login — auto-assign first player as admin
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        MatchData matchData = MatchData.get(server);
        if (!matchData.hasAdmin()) {
            matchData.setAdmin(player.getUUID());
            player.sendSystemMessage(
                    Component.translatable("command.ctf.admin.auto_assigned")
                            .withStyle(ChatFormatting.GOLD));
        }
    }

    // -------------------------------------------------------------------------
    // Block protection
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getState().getBlock() instanceof CapturePointBlock)) return;
        if (!event.getPlayer().getAbilities().instabuild) {
            event.setCanceled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Combat — friendly fire + neutral attacker blocking
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof Player defender)) return;
        Player attacker = null;
        if (event.getSource().getEntity() instanceof Player p) attacker = p;
        if (attacker == null || attacker == defender) return;
        if (!(defender.level() instanceof ServerLevel serverLevel)) return;

        TF1Team attackerTeam = TeamManager.getTeam(attacker.getUUID());
        TF1Team defenderTeam = TeamManager.getTeam(defender.getUUID());

        if (attackerTeam == null) { event.setCanceled(true); return; }

        if (attackerTeam == defenderTeam) {
            if (!TF1WorldData.get(serverLevel).isFriendlyFireEnabled()) {
                event.setCanceled(true);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Kill tracking
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) return;
        MinecraftServer server = killer.getServer();
        if (server == null) return;

        MatchData matchData = MatchData.get(server);
        if (matchData.getPhase() != GamePhase.ROUND_ACTIVE) return;

        TF1Team killerTeam = TeamManager.getTeam(killer.getUUID());
        if (killerTeam != null) matchData.addKill(killerTeam);
    }

    // -------------------------------------------------------------------------
    // Server tick — game loop + HUD + Tab
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null || server.getPlayerList().getPlayers().isEmpty()) return;

        MatchData matchData = MatchData.get(server);
        GamePhase phase = matchData.getPhase();

        // Game logic
        switch (phase) {
            case PREGAME      -> tickPregame(server, matchData);
            case ROUND_ACTIVE -> tickRoundActive(server, matchData);
            case ROUND_END    -> tickRoundEnd(server, matchData);
            default           -> {}
        }

        // Actionbar HUD (every 1 s during non-IDLE phases)
        if (phase != GamePhase.IDLE) {
            if (++hudUpdateCounter >= HUD_UPDATE_INTERVAL) {
                hudUpdateCounter = 0;
                Component hud = TF1Messages.buildHud(server, matchData);
                var hudPacket = new ClientboundSetActionBarTextPacket(hud);
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    p.connection.send(hudPacket);
                }
            }
        }

        // Tab list header (every 5 s)
        if (++tabUpdateCounter >= TAB_UPDATE_INTERVAL) {
            tabUpdateCounter = 0;
            Component header = TF1Messages.buildTabHeader(server, matchData);
            var tabPacket = new ClientboundTabListPacket(header, Component.empty());
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.connection.send(tabPacket);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pregame tick
    // -------------------------------------------------------------------------

    private void tickPregame(MinecraftServer server, MatchData matchData) {
        matchData.decPregameCountdown();
        int ticks = matchData.getPregameCountdownTicks();
        int seconds = ticks / 20;

        // Announce at 60, 50, 40, 30, 20, 10, 9, 8 ... 1 seconds remaining
        if (ticks % 20 == 0 && seconds > 0) {
            boolean announce = seconds <= 10 || seconds % 10 == 0;
            if (announce) {
                TF1Messages.sendGlobal(server,
                        Component.translatable("command.ctf.pregame.countdown",
                                Component.literal(String.valueOf(seconds))
                                        .withStyle(ChatFormatting.GREEN)));
            }
        }

        // Countdown finished → auto-assign + start round 1
        if (ticks <= 0) {
            CtfCommand.autoAssignTeams(server);
            matchData.startRound();
            TF1Messages.sendGlobal(server,
                    Component.translatable("command.ctf.round.start",
                            Component.literal(String.valueOf(matchData.getRoundNumber()))
                                    .withStyle(ChatFormatting.GOLD)));
        }
    }

    // -------------------------------------------------------------------------
    // Round active tick
    // -------------------------------------------------------------------------

    private void tickRoundActive(MinecraftServer server, MatchData matchData) {
        matchData.incRoundTimeTicks();

        // Accumulate hold time: each owned zone contributes 1 tick per server tick
        // A team owning N zones gains N ticks per server tick (N× speed)
        Map<TF1Team, Integer> zoneCount = new EnumMap<>(TF1Team.class);
        for (TF1Team t : TF1Team.values()) zoneCount.put(t, 0);
        for (TF1Team owner : TF1WorldData.get(server).getPointOwners().values()) {
            if (owner != null) zoneCount.merge(owner, 1, Integer::sum);
        }
        for (TF1Team t : TF1Team.values()) {
            int zones = zoneCount.getOrDefault(t, 0);
            if (zones > 0) matchData.addHoldTicks(t, zones);
        }

        long winThreshold = matchData.getHoldWinThresholdSeconds() * 20L;

        // Check immediate win: first team to reach the hold time threshold
        for (TF1Team t : TF1Team.values()) {
            if (matchData.getHoldTimeTicks(t) >= winThreshold) {
                TF1Messages.sendGlobal(server,
                        Component.translatable("command.ctf.round.winner_hold",
                                TF1Messages.teamComponent(t)));
                matchData.beginRoundEnd(t);
                finishRoundOrMatch(server, matchData);
                return;
            }
        }

        // Check round time limit
        long roundLimit = matchData.getRoundLimitSeconds() * 20L;
        if (matchData.getRoundTimeTicks() >= roundLimit) {
            TF1Team timeWinner = findTimeWinner(matchData);
            if (timeWinner != null) {
                TF1Messages.sendGlobal(server,
                        Component.translatable("command.ctf.round.winner_time",
                                TF1Messages.teamComponent(timeWinner)));
            } else {
                TF1Messages.sendGlobal(server,
                        Component.translatable("command.ctf.round.end_no_winner")
                                .withStyle(ChatFormatting.GRAY));
            }
            matchData.beginRoundEnd(timeWinner);
            finishRoundOrMatch(server, matchData);
        }
    }

    /** Returns the team with the most hold time, or null if all tied at 0 or tied for first. */
    @Nullable
    private TF1Team findTimeWinner(MatchData matchData) {
        TF1Team best = null;
        long bestTicks = 0;
        boolean tie = false;
        for (TF1Team t : TF1Team.values()) {
            long hold = matchData.getHoldTimeTicks(t);
            if (hold > bestTicks) {
                bestTicks = hold;
                best = t;
                tie = false;
            } else if (hold == bestTicks && hold > 0) {
                tie = true;
            }
        }
        return (bestTicks == 0 || tie) ? null : best;
    }

    /**
     * After a round ends: check if the match is over.
     * If yes → MATCH_END. If no → leave as ROUND_END and let tickRoundEnd() start next round.
     */
    private void finishRoundOrMatch(MinecraftServer server, MatchData matchData) {
        TF1Team matchWinner = matchData.checkMatchWinner();
        if (matchWinner != null) {
            matchData.setMatchWinner(matchWinner);
            matchData.setPhase(GamePhase.MATCH_END);
            TF1Messages.sendGlobal(server,
                    Component.translatable("command.ctf.match.winner",
                            TF1Messages.teamComponent(matchWinner))
                            .withStyle(ChatFormatting.GOLD));
            TF1Messages.sendGlobal(server,
                    Component.translatable("command.ctf.match.restart_hint")
                            .withStyle(ChatFormatting.GRAY));
        }
    }

    // -------------------------------------------------------------------------
    // Round end tick (brief pause before next round)
    // -------------------------------------------------------------------------

    private void tickRoundEnd(MinecraftServer server, MatchData matchData) {
        matchData.decRoundEndPause();
        if (matchData.getRoundEndPauseTicks() <= 0) {
            matchData.startRound();
            TF1Messages.sendGlobal(server,
                    Component.translatable("command.ctf.round.start",
                            Component.literal(String.valueOf(matchData.getRoundNumber()))
                                    .withStyle(ChatFormatting.GOLD)));
        }
    }
}

