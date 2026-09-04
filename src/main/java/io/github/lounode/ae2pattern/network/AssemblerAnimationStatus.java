package io.github.lounode.ae2pattern.network;

import net.minecraft.world.item.ItemStack;

/**
 * Client-side only state about the ongoing animation for a pattern disk assembler page.
 * Mirrors AE2's {@code AssemblerAnimationStatus} so the renderer can show the crafted item
 * and emit crafting particles right after a unit finishes a job.
 */
public class AssemblerAnimationStatus {

    private final ItemStack is;

    private final byte speed;

    private final int ticksRequired;

    private float accumulatedTicks;

    private float ticksUntilParticles;

    public AssemblerAnimationStatus(byte speed, ItemStack is) {
        this.speed = speed;
        this.is = is;
        // Guard against a zero/invalid speed: AE2 always uses >= 10, but a bad value must not
        // produce an instantly-expired animation (Integer overflow) or a never-ending one.
        this.ticksRequired = (int) Math.ceil(Math.max(1, 100.0f / Math.max(1, speed))) + 2;
    }

    public ItemStack getIs() {
        return is;
    }

    public byte getSpeed() {
        return speed;
    }

    public float getAccumulatedTicks() {
        return accumulatedTicks;
    }

    public void setAccumulatedTicks(float accumulatedTicks) {
        this.accumulatedTicks = accumulatedTicks;
    }

    public float getTicksUntilParticles() {
        return ticksUntilParticles;
    }

    public void setTicksUntilParticles(float ticksUntilParticles) {
        this.ticksUntilParticles = ticksUntilParticles;
    }

    public boolean isExpired() {
        return accumulatedTicks > ticksRequired;
    }
}
