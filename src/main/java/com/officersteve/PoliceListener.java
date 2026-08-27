package com.officersteve;

import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.NpcManager;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.events.NpcInteractEvent;
import de.oliver.fancynpcs.api.utils.NpcEquipmentSlot;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PoliceListener implements Listener {

    /** Officer Steve's health pool, in half-hearts (20.0 = 10 hearts, same as a player). */
    private static final double MAX_HEALTH = 20.0;

    /** Vanilla-style invulnerability window between hits, so click-spam can't one-shot him. */
    private static final long HURT_IMMUNITY_TICKS = 10L;

    /** He gives up and despawns this long after spawning if nobody kills him. */
    private static final long DESPAWN_AFTER_TICKS = 3L * 60L * 20L; // 3 minutes

    private static final double ATTACK_DAMAGE = 2.0;   // 1 heart
    private static final double ATTACK_RANGE = 2.5;    // blocks
    private static final double MOVE_SPEED = 0.6;      // blocks per tick-check (called every 4 ticks / ~0.2s, ~3 blocks/sec)
    private static final long ATTACK_COOLDOWN_TICKS = 20L; // 1 second between hits

    // The skin identifier used for Officer Steve. MHF_Steve is a long-standing
    // Mojang account with the classic default "Steve" skin, commonly used by
    // plugins for exactly this purpose. If it ever stops resolving, swap this
    // for another username with the default skin, or a direct skin URL/texture.
    private static final String STEVE_SKIN = "MHF_Steve";

    private final PoliceSteve plugin;

    // Keyed by the FancyNpcs NpcData id (data.getId(), NOT the name we pass in -
    // FancyNpcs generates its own random id, which is what NpcInteractEvent reports).
    private final Map<String, OfficerNpcState> activeOfficers = new ConcurrentHashMap<>();

    public PoliceListener(PoliceSteve plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.equals(victim)) {
            return;
        }

        spawnOfficer(victim.getLocation(), killer);
    }

    private void spawnOfficer(Location location, Player killer) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        NpcManager npcManager = FancyNpcsPlugin.get().getNpcManager();
        String npcName = "policesteve-" + UUID.randomUUID().toString().substring(0, 8);

        NpcData data = new NpcData(npcName, killer.getUniqueId(), location);
        data.setDisplayName(ChatColor.BLUE + "Officer Steve");
        data.setSkin(STEVE_SKIN);
        data.setTurnToPlayer(true);
        data.setShowInTab(false);
        // No interaction cooldown - we do our own hurt-immunity timing below, and a
        // FancyNpcs cooldown here would silently swallow hits and make him unkillable.
        data.setInteractionCooldown(0f);

        data.addEquipment(NpcEquipmentSlot.HEAD, blueLeather(Material.LEATHER_HELMET));
        data.addEquipment(NpcEquipmentSlot.CHEST, blueLeather(Material.LEATHER_CHESTPLATE));
        data.addEquipment(NpcEquipmentSlot.MAINHAND, new ItemStack(Material.IRON_SWORD));

        Npc npc = FancyNpcsPlugin.get().getNpcAdapter().apply(data);
        npcManager.registerNpc(npc);
        npc.create();
        npc.spawnForAll();

        long expireTick = plugin.getServer().getCurrentTick() + DESPAWN_AFTER_TICKS;

        // Key on the id FancyNpcs actually assigned, so left-click events can find him.
        activeOfficers.put(npc.getData().getId(),
                new OfficerNpcState(npc, killer.getUniqueId(), killer.getName(), MAX_HEALTH, expireTick));
    }

    private ItemStack blueLeather(Material material) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        if (meta != null) {
            meta.setColor(Color.BLUE);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Called every 4 ticks (~0.2s) from PoliceSteve's scheduler.
     * Handles the despawn timer, chasing the target, and dealing melee damage,
     * since FancyNpcs NPCs have no built-in AI or combat of their own.
     */
    public void tickOfficers() {
        if (activeOfficers.isEmpty()) {
            return;
        }

        long now = plugin.getServer().getCurrentTick();

        for (Map.Entry<String, OfficerNpcState> entry : activeOfficers.entrySet()) {
            String npcId = entry.getKey();
            OfficerNpcState state = entry.getValue();

            // Off-duty: he's been chasing for DESPAWN_AFTER_TICKS without being killed.
            if (state.hasExpired(now)) {
                giveUp(npcId, state);
                continue;
            }

            Player target = plugin.getServer().getPlayer(state.getTargetUuid());
            if (target == null || !target.isOnline()) {
                despawn(npcId, state);
                continue;
            }

            NpcData data = state.getNpc().getData();
            Location npcLoc = data.getLocation();
            Location targetLoc = target.getLocation();

            if (npcLoc.getWorld() == null || !npcLoc.getWorld().equals(targetLoc.getWorld())) {
                despawn(npcId, state);
                continue;
            }

            double distance = npcLoc.distance(targetLoc);

            if (distance <= ATTACK_RANGE) {
                if (now - state.getLastAttackTick() >= ATTACK_COOLDOWN_TICKS) {
                    target.damage(ATTACK_DAMAGE);

                    Vector knockback = npcLoc.toVector().subtract(targetLoc.toVector()).multiply(-1);
                    knockback.setY(0);
                    if (knockback.lengthSquared() > 0) {
                        knockback.normalize().multiply(0.4);
                    }
                    knockback.setY(0.25);
                    target.setVelocity(target.getVelocity().add(knockback));

                    target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.0f);
                    state.setLastAttackTick(now);
                }
            } else {
                // Straight-line homing toward the target. FancyNpcs NPCs have no
                // built-in pathfinding, so this won't navigate around obstacles
                // the way a real mob would - it just walks directly at the target.
                Vector direction = targetLoc.toVector().subtract(npcLoc.toVector());
                direction.setY(0);
                if (direction.lengthSquared() > 0.0001) {
                    direction.normalize().multiply(Math.min(MOVE_SPEED, distance));
                }

                Location newLoc = npcLoc.clone().add(direction);

                // Nudge height toward the target too, so he approximately follows
                // elevation changes (stairs, slopes) despite not truly pathfinding
                double deltaY = targetLoc.getY() - npcLoc.getY();
                double yStep = Math.max(-MOVE_SPEED, Math.min(MOVE_SPEED, deltaY));
                newLoc.setY(npcLoc.getY() + yStep);

                newLoc.setDirection(targetLoc.toVector().subtract(newLoc.toVector()));

                data.setLocation(newLoc);
                state.getNpc().moveForAll();
            }
        }
    }

    @EventHandler
    public void onNpcInteract(NpcInteractEvent event) {
        if (event.getInteractionType() != ActionTrigger.LEFT_CLICK) {
            return;
        }

        String npcId = event.getNpc().getData().getId();
        OfficerNpcState state = activeOfficers.get(npcId);
        if (state == null || state.isDead()) {
            return;
        }

        long now = plugin.getServer().getCurrentTick();
        if (now - state.getLastDamagedTick() < HURT_IMMUNITY_TICKS) {
            return; // still in his hurt-immunity window
        }
        state.setLastDamagedTick(now);

        Player attacker = event.getPlayer();
        double damage = weaponDamage(attacker.getInventory().getItemInMainHand());
        double remaining = state.damage(damage);

        Location npcLoc = state.getNpc().getData().getLocation();
        World world = npcLoc.getWorld();

        if (remaining > 0.0) {
            if (world != null) {
                world.playSound(npcLoc, Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);
                world.spawnParticle(Particle.DAMAGE_INDICATOR, npcLoc.clone().add(0, 1.0, 0), 4, 0.2, 0.4, 0.2, 0.0);
            }
            return;
        }

        // Health hit zero - Officer Steve goes down.
        killOfficer(npcId, state, attacker);
    }

    /**
     * Rough vanilla-style melee damage for whatever the attacker is holding, so
     * Officer Steve can be killed with real weapons rather than a fixed click count.
     * FancyNpcs NPCs aren't real entities, so nothing hooks this up for us.
     */
    private double weaponDamage(ItemStack held) {
        if (held == null) {
            return 1.0;
        }
        return switch (held.getType()) {
            case NETHERITE_SWORD -> 8.0;
            case DIAMOND_SWORD -> 7.0;
            case IRON_SWORD, NETHERITE_AXE -> 6.0;
            case STONE_SWORD, DIAMOND_AXE -> 5.0;
            case WOODEN_SWORD, GOLDEN_SWORD, IRON_AXE -> 4.0;
            case STONE_AXE, WOODEN_AXE, GOLDEN_AXE -> 3.0;
            case NETHERITE_PICKAXE, DIAMOND_PICKAXE, NETHERITE_SHOVEL, DIAMOND_SHOVEL -> 2.0;
            case TRIDENT -> 9.0;
            default -> 1.0; // fists and everything else
        };
    }

    /** Officer Steve is killed by a player: death effects, ticket drop, cleanup. */
    private void killOfficer(String npcId, OfficerNpcState state, Player killer) {
        Location npcLoc = state.getNpc().getData().getLocation();
        World world = npcLoc.getWorld();

        if (world != null) {
            world.playSound(npcLoc, Sound.ENTITY_PLAYER_DEATH, 1.0f, 1.0f);
            world.spawnParticle(Particle.CLOUD, npcLoc.clone().add(0, 1.0, 0), 20, 0.3, 0.6, 0.3, 0.02);
        }

        killer.sendMessage(ChatColor.BLUE + "Officer Steve" + ChatColor.GRAY + " has been taken down.");

        dropTicket(state);
        despawn(npcId, state);
    }

    /** The 3-minute timer ran out: he goes off duty quietly, with no ticket. */
    private void giveUp(String npcId, OfficerNpcState state) {
        Location npcLoc = state.getNpc().getData().getLocation();
        World world = npcLoc.getWorld();
        if (world != null) {
            world.playSound(npcLoc, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7f, 1.2f);
            world.spawnParticle(Particle.SMOKE, npcLoc.clone().add(0, 1.0, 0), 20, 0.3, 0.6, 0.3, 0.02);
        }

        Player target = plugin.getServer().getPlayer(state.getTargetUuid());
        if (target != null && target.isOnline()) {
            target.sendMessage(ChatColor.BLUE + "Officer Steve" + ChatColor.GRAY + " has clocked off. You got away with it.");
        }

        despawn(npcId, state);
    }

    private void dropTicket(OfficerNpcState state) {
        Location dropLocation = state.getNpc().getData().getLocation();
        World world = dropLocation.getWorld();
        if (world == null) {
            return;
        }

        ItemStack ticket = new ItemStack(Material.PAPER);
        ItemMeta meta = ticket.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Ticket to Jaildonia for " + state.getTargetName());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Issued by the Jaildonia Police Department");
            lore.add(ChatColor.GRAY + "Wanted for: Murder");
            meta.setLore(lore);
            ticket.setItemMeta(meta);
        }

        world.dropItemNaturally(dropLocation, ticket);
    }

    private void despawn(String npcId, OfficerNpcState state) {
        if (activeOfficers.remove(npcId) == null) {
            return; // already cleaned up
        }
        Npc npc = state.getNpc();
        npc.removeForAll();
        FancyNpcsPlugin.get().getNpcManager().removeNpc(npc);
    }

    public void removeAllOfficers() {
        for (Map.Entry<String, OfficerNpcState> entry : activeOfficers.entrySet()) {
            OfficerNpcState state = entry.getValue();
            state.getNpc().removeForAll();
            FancyNpcsPlugin.get().getNpcManager().removeNpc(state.getNpc());
        }
        activeOfficers.clear();
    }
}
