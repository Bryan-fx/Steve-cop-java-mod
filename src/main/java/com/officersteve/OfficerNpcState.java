package com.officersteve;

import org.bukkit.entity.Mannequin;

import java.util.UUID;

/**
 * Tracks one active "Officer Steve" mannequin: which player he's after, when he
 * clocks off, and his melee cooldown.
 *
 * Health is NOT tracked here any more - the mannequin is a real LivingEntity, so
 * vanilla owns its health, damage and death.
 */
public class OfficerNpcState {

    private final Mannequin mannequin;
    private final UUID targetUuid;
    private final String targetName;

    /** Server tick at which this officer despawns if he hasn't been killed. */
    private final long expireTick;

    private long lastAttackTick;

    public OfficerNpcState(Mannequin mannequin, UUID targetUuid, String targetName, long expireTick) {
        this.mannequin = mannequin;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.expireTick = expireTick;
        this.lastAttackTick = 0L;
    }

    public Mannequin getMannequin() {
        return mannequin;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public long getExpireTick() {
        return expireTick;
    }

    public boolean hasExpired(long currentTick) {
        return currentTick >= expireTick;
    }

    public long getLastAttackTick() {
        return lastAttackTick;
    }

    public void setLastAttackTick(long tick) {
        this.lastAttackTick = tick;
    }
}
