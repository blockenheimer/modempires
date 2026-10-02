package com.example.blockexport;

import net.fabricmc.fabric.api.rendering.data.v1.RenderAttachedBlockView;
import net.fabricmc.fabric.api.rendering.data.v1.RenderAttachmentBlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * Faz o ClientLevel se comportar como a "regiao de chunk" que o renderer do Fabric passa aos modelos.
 *
 * Os modelos do Fabric (inclusive o CopycatModel do Create Fabric / Copycats+) leem o material copiado
 * via RenderAttachedBlockView.getBlockEntityRenderAttachment(pos), que so existe nessa regiao de chunk.
 * Este wrapper entrega o mesmo dado a partir do ClientLevel.
 */
final class ExportView implements RenderAttachedBlockView {
    private final ClientLevel level;

    ExportView(ClientLevel level) {
        this.level = level;
    }

    // --- BlockGetter / LevelHeightAccessor
    public BlockEntity getBlockEntity(BlockPos pos) { return level.getBlockEntity(pos); }
    public BlockState getBlockState(BlockPos pos) { return level.getBlockState(pos); }
    public FluidState getFluidState(BlockPos pos) { return level.getFluidState(pos); }
    public int getHeight() { return level.getHeight(); }
    public int getMinBuildHeight() { return level.getMinBuildHeight(); }

    // --- BlockAndTintGetter
    public float getShade(Direction dir, boolean shaded) { return level.getShade(dir, shaded); }
    public LevelLightEngine getLightEngine() { return level.getLightEngine(); }
    public int getBlockTint(BlockPos pos, ColorResolver resolver) { return level.getBlockTint(pos, resolver); }

    // --- Fabric
    public Object getBlockEntityRenderAttachment(BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof RenderAttachmentBlockEntity r ? r.getRenderAttachmentData() : null;
    }
}
