package ru.madmax.tf1.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ru.madmax.tf1.data.TF1WorldData;
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
                                    Component.translatable("command.tf1.error.unknown_team", input)
                                );
                                return 0;
                            }

                            TeamManager.assignTeam(player.getUUID(), team);
                            ScoreboardHelper.syncPlayerTeam(
                                    ctx.getSource().getServer(), player, team);
                            equipTeamHelmet(player, team);

                            ctx.getSource().sendSuccess(
                                () -> Component.translatable("message.tf1.team.joined",
                                        TF1Messages.teamComponent(team)),
                                false
                            );
                            return 1;
                        })
                    )
                )

                // /tf1 leave
                .then(Commands.literal("leave")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        TeamManager.leaveTeam(player.getUUID());
                        ScoreboardHelper.syncPlayerTeam(
                                ctx.getSource().getServer(), player, null);
                        removeTeamHelmet(player);
                        ctx.getSource().sendSuccess(
                            () -> Component.translatable("message.tf1.team.left"),
                            false
                        );
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
                                () -> Component.translatable("message.tf1.team.none"),
                                false
                            );
                        } else {
                            ctx.getSource().sendSuccess(
                                () -> Component.translatable("message.tf1.team.current_team",
                                        TF1Messages.teamComponent(team)),
                                false
                            );
                        }
                        return 1;
                    })
                )

                // /tf1 capturetime <seconds>  (OP level 2 required)
                .then(Commands.literal("capturetime")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                        .executes(ctx -> {
                            int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                            TF1WorldData.get(ctx.getSource().getServer())
                                    .setCaptureTimeSeconds(seconds);
                            ctx.getSource().sendSuccess(
                                () -> Component.translatable("command.tf1.capturetime.set", seconds),
                                true
                            );
                            return 1;
                        })
                    )
                )

                // /tf1 friendlyfire <true|false>  (OP level 2 required)
                .then(Commands.literal("friendlyfire")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                            TF1WorldData.get(ctx.getSource().getServer())
                                    .setFriendlyFireEnabled(enabled);
                            String key = enabled
                                    ? "command.tf1.friendlyfire.enabled"
                                    : "command.tf1.friendlyfire.disabled";
                            ctx.getSource().sendSuccess(
                                () -> Component.translatable(key),
                                true
                            );
                            return 1;
                        })
                    )
                )

                // /tf1 status
                .then(Commands.literal("status")
                    .executes(ctx -> {
                        for (Component line : TF1Messages.buildStatusLines(
                                ctx.getSource().getServer())) {
                            ctx.getSource().sendSuccess(() -> line, false);
                        }
                        return 1;
                    })
                )
        );
    }

    // -------------------------------------------------------------------------
    // Helmet helpers
    // -------------------------------------------------------------------------

    private static final String HELMET_TAG = "tf1_team_helmet";

    /**
     * Equips a dyed leather helmet in the player's head slot.
     * Uses vanilla NBT display color (works without casting to any specific class).
     * Replaces an existing TF1 helmet if present; does NOT replace other helmets.
     */
    public static void equipTeamHelmet(ServerPlayer player, TF1Team team) {
        ItemStack current = player.getItemBySlot(EquipmentSlot.HEAD);
        // Only replace if slot is empty or already a TF1 helmet
        if (!current.isEmpty() && !isTF1Helmet(current)) return;

        ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
        CompoundTag display = helmet.getOrCreateTagElement("display");
        display.putInt("color", team.helmetColor);
        helmet.getOrCreateTag().putBoolean(HELMET_TAG, true);
        player.setItemSlot(EquipmentSlot.HEAD, helmet);
    }

    /** Removes the TF1 team helmet from the player's head slot, if present. */
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
