package studio.antagon.inventory.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import studio.antagon.inventory.InventoryConfig;

/** Access to the world owner's integrated server, including Essential-hosted worlds. */
public final class HostStorage {
    private static final int[] STORAGE = IntStream.range(9, 36).toArray();
    public record Result(int moved, int chests, String status) { }

    public static Result deposit(MinecraftServer server, UUID owner, ResourceKey<Level> dimension,
        int requestedRadius, AtomicBoolean cancelled) {
        if (!server.isSameThread()) throw new IllegalStateException("O acesso aos baús deve ocorrer na tarefa do servidor");
        var player = server.getPlayerList().getPlayer(owner);
        if (player == null || !server.isSingleplayerOwner(new NameAndId(owner, player.getName().getString())))
            return new Result(0, 0, "host_only");
        if (cancelled.get() || player.isDeadOrDying() || player.isSpectator()
            || !player.level().dimension().equals(dimension)
            || player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty())
            return new Result(0, 0, "cancelled");
        var level = player.level();
        int radius = InventoryConfig.clamp(requestedRadius);
        BlockPos origin = player.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        // Search existing block entities, not every block in a potentially large radius. Never load chunks.
        for (int x = Math.floorDiv(origin.getX() - radius, 16); x <= Math.floorDiv(origin.getX() + radius, 16); x++)
            for (int z = Math.floorDiv(origin.getZ() - radius, 16); z <= Math.floorDiv(origin.getZ() + radius, 16); z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var entity : chunk.getBlockEntities().values()) {
                    if (!(entity instanceof ChestBlockEntity) || entity.isRemoved()) continue;
                    BlockPos pos = entity.getBlockPos();
                    if (pos.distSqr(origin) <= radius * radius) candidates.add(pos.immutable());
                }
            }
        candidates.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(player.position())));
        Set<BlockPos> seen = new HashSet<>();
        int moved = 0, chests = 0;
        try {
            for (BlockPos pos : candidates) {
                if (cancelled.get()) break;
                if (IntStream.of(STORAGE).allMatch(index -> player.getInventory().getItem(index).isEmpty())) break;
                if (seen.contains(pos)) continue;
                var state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof ChestBlock chest)) continue;
                if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity first)) continue;
                // Reading an unexplored loot chest would generate loot; leave those untouched.
                if (first.getLootTable() != null || first.isLocked()) continue;
                if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    BlockPos connected = ChestBlock.getConnectedBlockPos(pos, state);
                    if (level.getChunkSource().getChunkNow(connected.getX() >> 4, connected.getZ() >> 4) == null
                        || !(level.getBlockEntity(connected) instanceof ChestBlockEntity second)
                        || second.getLootTable() != null || second.isLocked()) continue;
                    seen.add(connected);
                }
                seen.add(pos);
                // Ignore lid obstructions: this reads the inventory without opening the lid or a GUI.
                var inventory = ChestBlock.getContainer(chest, state, level, pos, true);
                if (inventory == null) continue;
                chests++;
                moved += StorageTransfer.matching(player.getInventory(), STORAGE, inventory);
            }
        } finally {
            // Synchronize the owner and any guest viewing one of these containers through normal server updates.
            player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
        }
        return new Result(moved, chests, cancelled.get() ? "cancelled" : chests == 0 ? "no_chests_host" : moved == 0 ? "nothing_sent" : "sent");
    }
    private HostStorage() { }
}
