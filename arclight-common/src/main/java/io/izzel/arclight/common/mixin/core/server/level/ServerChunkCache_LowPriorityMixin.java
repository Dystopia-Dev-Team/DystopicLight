package io.izzel.arclight.common.mixin.core.server.level;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheBridge;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerChunkCache.class, priority = 1001)
public abstract class ServerChunkCache_LowPriorityMixin implements ServerChunkCacheBridge {

    // Paper start
    @Unique
    private final ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable<net.minecraft.world.level.chunk.LevelChunk> fullChunks = new ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable<>();
    // Paper end

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
    private void optimiseGetChunkAtCallForLoadedChunks(int x, int z, ChunkStatus chunkStatus, boolean requireChunk, CallbackInfoReturnable<ChunkAccess> cir) {
        // Paper start - rewrite chunk system
        if (chunkStatus == ChunkStatus.FULL) {
            final LevelChunk ret = this.fullChunks.get(ChunkPos.asLong(x, z));

            if (ret != null) {
                cir.setReturnValue(ret);
            }
        }
        // Paper end - rewrite chunk system
    }

    /**
     * @author MemencioPerez
     * @reason Optimise getChunkAt calls for loaded chunks
     */
    @Overwrite
    public @org.jetbrains.annotations.Nullable LevelChunk getChunkNow(int chunkX, int chunkZ) {
        return this.fullChunks.get(ChunkPos.asLong(chunkX, chunkZ)); // Paper - rewrite chunk system
    }
}
