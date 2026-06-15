package ru.madmax.tf1.event;

import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ru.madmax.tf1.block.CapturePointBlock;
import ru.madmax.tf1.data.TF1WorldData;
import ru.madmax.tf1.team.TF1Team;
import ru.madmax.tf1.team.TeamManager;
import ru.madmax.tf1.util.ScoreboardHelper;
import ru.madmax.tf1.util.TF1Messages;

public class TF1EventHandlers {

    /** Ticks between tab list header updates (100 ticks = 5 seconds). */
    private static final int TAB_UPDATE_INTERVAL = 100;
    private int tabUpdateCounter = 0;

    /**
     * Initializes scoreboard teams and clears stale memberships so that the
     * in-memory TeamManager and the persisted scoreboard do not diverge.
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ScoreboardHelper.ensureTeams(server);
        ScoreboardHelper.clearAllTF1Memberships(server);
    }

    /**
     * Prevents Survival-mode players from breaking capture point blocks.
     * Creative mode players may still break them normally.
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getState().getBlock() instanceof CapturePointBlock)) return;
        Player player = event.getPlayer();
        if (!player.getAbilities().instabuild) {
            event.setCanceled(true);
        }
    }

    /**
     * Handles two cases of blocked player damage:
     * <ol>
     *   <li>A neutral player (no TF1 team) cannot deal damage to other players.</li>
     *   <li>When friendly fire is disabled, teammates cannot damage each other.</li>
     * </ol>
     * A neutral player may still receive damage from anyone.
     */
    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        // Only intercept player-vs-player damage
        if (!(event.getEntity() instanceof Player defender)) return;

        // Determine the attacking player (direct melee or indirect projectile owner)
        Player attacker = null;
        if (event.getSource().getEntity() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null || attacker == defender) return;

        // Server-side only
        if (!(defender.level() instanceof ServerLevel serverLevel)) return;

        TF1Team attackerTeam = TeamManager.getTeam(attacker.getUUID());
        TF1Team defenderTeam = TeamManager.getTeam(defender.getUUID());

        // Neutral attacker cannot damage other players
        if (attackerTeam == null) {
            event.setCanceled(true);
            return;
        }

        // Same-team damage blocked when friendly fire is off
        if (attackerTeam == defenderTeam) {
            if (!TF1WorldData.get(serverLevel).isFriendlyFireEnabled()) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * Periodically refreshes the Tab key header for all online players.
     * Shows current team distribution and capture point statuses.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tabUpdateCounter < TAB_UPDATE_INTERVAL) return;
        tabUpdateCounter = 0;

        MinecraftServer server = event.getServer();
        if (server == null || server.getPlayerList().getPlayers().isEmpty()) return;

        var header = TF1Messages.buildTabHeader(server);
        var footer = net.minecraft.network.chat.Component.empty();
        var packet = new ClientboundTabListPacket(header, footer);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(packet);
        }
    }
}
