package com.officersteve;

import de.oliver.fancynpcs.api.Npc;

import java.util.UUID;

/**
 * Tracks one active "Officer Steve" NPC: which player he's after,
 * how many more hits will take him down, and simple attack cooldown state.
 */
public class OfficerNpcState {

    private final Npc npc;
    private final UUID targetUuid;
    private final String targetName;

    private int hitsRemaining;
    private long lastAttackTick;

    public OfficerNpcState(Npc npc, UUID targetUuid, String targetName, int hitsRemaining) {
        this.npc = npc;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.hitsRemaining = hitsRemaining;
        this.lastAttackTick = 0L;
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

    public int getHitsRemaining() {
        return hitsRemaining;
    }

    /** @return the remaining hit count after this hit */
    public int registerHit() {
        hitsRemaining--;
        return hitsRemaining;
    }

    public long getLastAttackTick() {
        return lastAttackTick;
    }

    public void setLastAttackTick(long tick) {
        this.lastAttackTick = tick;
    }
}
