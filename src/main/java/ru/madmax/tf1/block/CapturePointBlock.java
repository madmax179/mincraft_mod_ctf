package ru.madmax.tf1.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import ru.madmax.tf1.data.TF1WorldData;
import ru.madmax.tf1.registry.ModBlockEntities;

import javax.annotation.Nullable;
import java.util.*;

public class CapturePointBlock extends BaseEntityBlock {

    public CapturePointBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(2.0f, 6.0f)
                .requiresCorrectToolForDrops());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CapturePointBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModBlockEntities.CAPTURE_POINT_BE.get(), CapturePointBlockEntity::tick);
    }

    /**
     * When a capture point block is removed (Creative break), detect if the
     * remaining neighbors split into multiple disconnected clusters.
     * If so, allocate new pointIds for all clusters except the first.
     *
     * We run the BFS BEFORE calling super.onRemove() so that block entities
     * of neighbors are still accessible. We treat {@code pos} as absent by
     * excluding it from BFS traversal.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && state.hasBlockEntity() && !newState.is(this)) {
            handleClusterSplit(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static void handleClusterSplit(Level level, BlockPos removedPos) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Read the removed block's pointId before super.onRemove() destroys the block entity.
        int removedPointId = 0;
        if (level.getBlockEntity(removedPos) instanceof CapturePointBlockEntity removedBe) {
            removedPointId = removedBe.getPointId();
        }

        // BFS from each face-adjacent neighbor, treating removedPos as absent.
        // Each successful BFS from an unvisited neighbor = one connected component.
        Set<BlockPos> globalVisited = new HashSet<>();
        globalVisited.add(removedPos); // skip the block being removed
        List<Set<BlockPos>> components = new ArrayList<>();

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = removedPos.relative(dir);
            if (globalVisited.contains(neighbor)) continue;
            if (!(level.getBlockEntity(neighbor) instanceof CapturePointBlockEntity)) continue;

            Set<BlockPos> component = new HashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(neighbor);
            globalVisited.add(neighbor);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                component.add(current);
                for (Direction d : Direction.values()) {
                    BlockPos next = current.relative(d);
                    if (!globalVisited.contains(next) &&
                            level.getBlockEntity(next) instanceof CapturePointBlockEntity) {
                        globalVisited.add(next);
                        queue.add(next);
                    }
                }
            }
            components.add(component);
        }

        TF1WorldData worldData = TF1WorldData.get(serverLevel);

        // No neighbors survived: the removed block was the sole member of its cluster.
        if (components.isEmpty()) {
            if (removedPointId > 0) {
                worldData.removePointOwner(removedPointId);
            }
            return;
        }

        // If 2+ components formed: first keeps original pointId, others get new IDs.
        if (components.size() > 1) {
            for (int i = 1; i < components.size(); i++) {
                int newId = worldData.allocatePointId();
                for (BlockPos memberPos : components.get(i)) {
                    if (level.getBlockEntity(memberPos) instanceof CapturePointBlockEntity cpbe) {
                        cpbe.setPointId(newId);
                    }
                }
            }
        }
    }
}
