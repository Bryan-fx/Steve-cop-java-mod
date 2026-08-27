package com.officersteve;

import de.oliver.fancynpcs.api.Npc;

import java.util.UUID;

/**
 * Tracks one active "Officer Steve" NPC: which player he's after, how much
 * health he has left, when he gives up and despawns, and simple cooldown state.
 */
public class OfficerNpcState {

    private final Npc npc;
    private final UUID targetUuid;
    private final String targetName;

    /** Server tick at which this officer despawns if he hasn't been killed. */
    private final long expireTick;

    private final double maxHealth;
    private double health;

    private long lastAttackTick;
    private long lastDamagedTick;
    private boolean dead;

    public OfficerNpcState(Npc npc, UUID targetUuid, String targetName, double maxHealth, long expireTick) {
        this.npc = npc;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.maxHealth = maxHealth;
        this.health = maxHealth;
        this.expireTick = expireTick;
        this.lastAttackTick = 0L;
        this.lastDamagedTick = Long.MIN_VALUE;
        this.dead = false;
    }

    public Npc getNpc() {
        return npc;
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

    public double getHealth() {
        return health;
    }

    public double getMaxHealth() {
        return maxHealth;
    }

    public boolean isDead() {
        return dead;
    }

    /**
     * Applies damage to this officer.
     *
     * @return the remaining health after the hit (never below zero)
     */
    public double damage(double amount) {
        health = Math.max(0.0, health - amount);
        if (health <= 0.0) {
            dead = true;
        }
        return health;
    }

    public long getLastAttackTick() {
        return lastAttackTick;
    }

    public void setLastAttackTick(long tick) {
        this.lastAttackTick = tick;
    }

    public long getLastDamagedTick() {
        return lastDamagedTick;
    }

    public void setLastDamagedTick(long tick) {
        this.lastDamagedTick = tick;
    }
}
