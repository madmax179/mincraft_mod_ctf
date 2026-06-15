package ru.madmax.tf1.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ru.madmax.tf1.data.MatchData;
import ru.madmax.tf1.data.TF1WorldData;
import ru.madmax.tf1.game.GamePhase;
import ru.madmax.tf1.team.TF1Team;
import ru.madmax.tf1.team.TeamManager;
import ru.madmax.tf1.util.ScoreboardHelper;
import ru.madmax.tf1.util.TF1Messages;

public class TF1Command {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("tf1")

                // /tf1 join <team>
                .then(Commands.literal("join")
                    .then(Commands.argument("team", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (TF1Team t : TF1Team.values()) {
                                builder.suggest(t.name().toLowerCase());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "team");
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            TF1Team team;
                            try {
                                team = TF1Team.valueOf(input.toUpperCase());
                            } catch (IllegalArgumentException e) {
                                ctx.getSource().sendFailure(
                                    Component.translatable("command.tf1.error.unknown_team", input));
                                return 0;
                            }
                            TeamManager.assignTeam(player.getUUID(), team);
                            ScoreboardHelper.syncPlayerTeam(ctx.getSource().getServer(), player, team);
                            equipTeamHelmet(player, team);
                            ctx.getSource().sendSuccess(
                                () -> Component.translatable("message.tf1.team.joined",
                                        TF1Messages.teamComponent(team)),
                                false);
                            return 1;
                        })
                    )
                )

                // /tf1 leave
                .then(Commands.literal("leave")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        TeamManager.leaveTeam(player.getUUID());
                        ScoreboardHelper.syncPlayerTeam(ctx.getSource().getServer(), player, null);
                        removeTeamHelmet(player);
                        ctx.getSource().sendSuccess(
                            () -> Component.translatable("message.tf1.team.left"),
                            false);
                        return 1;
                    })
                )

                // /tf1 team
                .then(Commands.literal("team")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        TF1Team team = TeamManager.getTeam(player.getUUID());
                        if (team == null) {
                            ctx.getSource().sendSuccess(
                                () -> Component.translatable("message.tf1.team.none"), false);
                        } else {
                            ctx.getSource().sendSuccess(
                                () -> Component.translatable("message.tf1.team.current_team",
                                        TF1Messages.teamComponent(team)),
                                false);
                        }
                        return 1;
                    })
                )

                // /tf1 capturetime <seconds>  - admin or OP 2
                .then(Commands.literal("capturetime")
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            MatchData matchData = MatchData.get(ctx.getSource().getServer());
                            if (!CtfCommand.isAdmin(player, matchData)) {
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
                )

                // /tf1 friendlyfire <true|false>  - admin or OP 2
                .then(Commands.literal("friendlyfire")
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            MatchData matchData = MatchData.get(ctx.getSource().getServer());
                            if (!CtfCommand.isAdmin(player, matchData)) {
                                ctx.getSource().sendFailure(
                                    Component.translatable("command.ctf.error.not_admin"));
                                return 0;
                            }
                            boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                            TF1WorldData.get(ctx.getSource().getServer()).setFriendlyFireEnabled(enabled);
                            String key = enabled
                                    ? "command.tf1.friendlyfire.enabled"
                                    : "command.tf1.friendlyfire.disabled";
                            ctx.getSource().sendSuccess(() -> Component.translatable(key), true);
                            return 1;
                        })
                    )
                )

                // /tf1 status
                .then(Commands.literal("status")
                    .executes(ctx -> {
                        for (Component line : TF1Messages.buildStatusLines(ctx.getSource().getServer())) {
                            ctx.getSource().sendSuccess(() -> line, false);
                        }
                        return 1;
                    })
                )

                // /tf1 start - any player; starts pregame
                .then(Commands.literal("start")
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

                        CtfCommand.fullResetAndClearTeams(server, matchData);
                        matchData.startPregame();

                        TF1Messages.sendGlobal(server,
                            Component.translatable("command.ctf.start.announced",
                                Component.literal(player.getName().getString())
                                        .withStyle(ChatFormatting.YELLOW),
                                Component.literal(String.valueOf(matchData.getPregameSeconds()))
                                        .withStyle(ChatFormatting.GREEN)));
                        return 1;
                    })
                )

                // /tf1 stop - admin only
                .then(Commands.literal("stop")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        MinecraftServer server = ctx.getSource().getServer();
                        MatchData matchData = MatchData.get(server);

                        if (!CtfCommand.isAdmin(player, matchData)) {
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
                )

                // /tf1 restart - admin only
                .then(Commands.literal("restart")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        MinecraftServer server = ctx.getSource().getServer();
                        MatchData matchData = MatchData.get(server);

                        if (!CtfCommand.isAdmin(player, matchData)) {
                            ctx.getSource().sendFailure(
                                Component.translatable("command.ctf.error.not_admin"));
                            return 0;
                        }

                        CtfCommand.fullResetAndClearTeams(server, matchData);
                        TF1Messages.sendGlobal(server,
                            Component.translatable("command.ctf.restart.restarted")
                                    .withStyle(ChatFormatting.GOLD));
                        return 1;
                    })
                )

                // /tf1 admin claim | /tf1 admin set <player>
                .then(Commands.literal("admin")
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

                                if (!CtfCommand.isAdmin(sender, matchData)) {
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
                )
        );
    }

    // ---- Helmet helpers (also used by CtfCommand) ----

    private static final String HELMET_TAG = "tf1_team_helmet";

    public static void equipTeamHelmet(ServerPlayer player, TF1Team team) {
        ItemStack current = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!current.isEmpty() && !isTF1Helmet(current)) return;
        ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
        CompoundTag display = helmet.getOrCreateTagElement("display");
        display.putInt("color", team.helmetColor);
        helmet.getOrCreateTag().putBoolean(HELMET_TAG, true);
        player.setItemSlot(EquipmentSlot.HEAD, helmet);
    }

    public static void removeTeamHelmet(ServerPlayer player) {
        ItemStack current = player.getItemBySlot(EquipmentSlot.HEAD);
        if (isTF1Helmet(current)) {
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        }
    }

    private static boolean isTF1Helmet(ItemStack stack) {
        return !stack.isEmpty()
                && stack.is(Items.LEATHER_HELMET)
                && stack.hasTag()
                && stack.getTag().getBoolean(HELMET_TAG);
    }
}
