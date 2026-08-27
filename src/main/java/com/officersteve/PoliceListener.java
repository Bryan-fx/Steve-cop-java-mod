package com.officersteve;

import io.papermc.paper.datacomponent.item.ResolvableProfile;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class PoliceListener implements Listener {

    /** He gives up and despawns this long after spawning if nobody kills him. */
    private static final long DESPAWN_AFTER_TICKS = 3L * 60L * 20L; // 3 minutes

    private static final double ATTACK_DAMAGE = 2.0;   // 1 heart
    private static final double ATTACK_RANGE = 2.5;    // blocks
    private static final double MOVE_SPEED = 0.6;      // blocks per tick-check (called every 4 ticks / ~0.2s, ~3 blocks/sec)
    private static final long ATTACK_COOLDOWN_TICKS = 20L; // 1 second between hits

    /**
     * Always the classic wide Steve skin. Mannequin.defaultProfile() leaves the
     * skin unset, which makes the client pick one of the nine default skins from
     * the entity UUID - so officers came out as Alex, Sunny, Zuri and so on. A
     * skin patch overrides rendering directly, with no profile lookup involved.
     */
    private static final ResolvableProfile STEVE_PROFILE = ResolvableProfile.resolvableProfile()
            .skinPatch(patch -> patch
                    .body(Key.key("minecraft", "entity/player/wide/steve"))
                    .model(PlayerTextures.SkinModel.CLASSIC))
            .build();

    private final PoliceSteve plugin;

    /** Marks a mannequin as one of ours, so we never touch decorative ones. */
    private final NamespacedKey officerKey;

    // Keyed by the mannequin's entity UUID
    private final Map<UUID, OfficerNpcState> activeOfficers = new ConcurrentHashMap<>();

    public PoliceListener(PoliceSteve plugin) {
        this.plugin = plugin;
        this.officerKey = new NamespacedKey(plugin, "officer_target");
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

        final Mannequin officer;
        try {
            officer = world.spawn(location, Mannequin.class, m -> {
                m.setProfile(STEVE_PROFILE);

                m.customName(Component.text("Officer Steve", NamedTextColor.BLUE));
                m.setCustomNameVisible(true);
                m.setDescription(null); // drop the default "NPC" line under the name

                // Movable so knockback reads properly; we drive position ourselves.
                m.setImmovable(false);
                m.setPersistent(false); // never written to the region file

                EntityEquipment equipment = m.getEquipment();
                equipment.setHelmet(blueLeather(Material.LEATHER_HELMET));
                equipment.setChestplate(blueLeather(Material.LEATHER_CHESTPLATE));
                equipment.setItemInMainHand(new ItemStack(Material.IRON_SWORD));

                m.getPersistentDataContainer().set(officerKey, PersistentDataType.STRING,
                        killer.getUniqueId().toString());
            });
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            // Log the real cause and a stack trace rather than guessing at why.
            plugin.getLogger().log(Level.WARNING, "Could not spawn Officer Steve", ex);
            return;
        }

        long expireTick = plugin.getServer().getCurrentTick() + DESPAWN_AFTER_TICKS;
        activeOfficers.put(officer.getUniqueId(),
                new OfficerNpcState(officer, killer.getUniqueId(), killer.getName(), expireTick));
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
     * Handles the despawn timer, chasing the target, and swinging at him.
     * Mannequins have no AI of their own, so movement is still ours to drive -
     * but health, damage and death are all vanilla now.
     */
    public void tickOfficers() {
        if (activeOfficers.isEmpty()) {
            return;
        }

        long now = plugin.getServer().getCurrentTick();

        for (Map.Entry<UUID, OfficerNpcState> entry : activeOfficers.entrySet()) {
            UUID id = entry.getKey();
            OfficerNpcState state = entry.getValue();
            Mannequin officer = state.getMannequin();

            // Killed, unloaded with its chunk, or otherwise gone.
            if (!officer.isValid()) {
                activeOfficers.remove(id);
                continue;
            }

            // Off duty: he's been chasing for DESPAWN_AFTER_TICKS without being killed.
            if (state.hasExpired(now)) {
                giveUp(state);
                continue;
            }

            Player target = plugin.getServer().getPlayer(state.getTargetUuid());
            if (target == null || !target.isOnline()) {
                despawn(state);
                continue;
            }

            Location npcLoc = officer.getLocation();
            Location targetLoc = target.getLocation();

            if (!npcLoc.getWorld().equals(targetLoc.getWorld())) {
                despawn(state);
                continue;
            }

            double distance = npcLoc.distance(targetLoc);

            if (distance <= ATTACK_RANGE) {
                if (now - state.getLastAttackTick() >= ATTACK_COOLDOWN_TICKS) {
                    officer.swingMainHand();
                    // Damaging with the officer as the source means vanilla handles
                    // knockback, hurt animation and death-message attribution.
                    target.damage(ATTACK_DAMAGE, officer);
                    state.setLastAttackTick(now);
                }
                officer.lookAt(targetLoc.getX(), targetLoc.getY(), targetLoc.getZ(),
                        io.papermc.paper.entity.LookAnchor.EYES);
            } else {
                // Straight-line homing toward the target. Mannequins have no
                // pathfinding, so this won't navigate around obstacles the way a
                // real mob would - it just walks directly at the target.
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

                officer.teleport(newLoc);
            }
        }
    }

    /**
     * Officers are only meant to die in a fight, so shrug off environmental chip
     * damage from being teleported through the world (fall, suffocation, drowning,
     * fire). Anything a player or mob does still lands.
     */
    @EventHandler
    public void onOfficerDamaged(EntityDamageEvent event) {
        if (!isOfficer(event.getEntity())) {
            return;
        }

        switch (event.getCause()) {
            case FALL, SUFFOCATION, DROWNING, FIRE, FIRE_TICK, HOT_FLOOR, CRAMMING, VOID -> event.setCancelled(true);
            default -> { }
        }
    }

    /** Vanilla killed him for us - clear the default drops and issue the ticket. */
    @EventHandler
    public void onOfficerDeath(EntityDeathEvent event) {
        if (!isOfficer(event.getEntity())) {
            return;
        }

        OfficerNpcState state = activeOfficers.remove(event.getEntity().getUniqueId());
        event.getDrops().clear();
        event.setDroppedExp(0);

        if (state == null) {
            return; // ours, but we've lost track of it (server reload) - nothing to issue
        }

        event.getDrops().add(ticketFor(state.getTargetName()));

        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            killer.sendMessage(Component.text("Officer Steve", NamedTextColor.BLUE)
                    .append(Component.text(" has been taken down.", NamedTextColor.GRAY)));
        }
    }

    private boolean isOfficer(org.bukkit.entity.Entity entity) {
        return entity instanceof Mannequin
                && entity.getPersistentDataContainer().has(officerKey, PersistentDataType.STRING);
    }

    /** The 3-minute timer ran out: he goes off duty quietly, with no ticket. */
    private void giveUp(OfficerNpcState state) {
        Location npcLoc = state.getMannequin().getLocation();
        World world = npcLoc.getWorld();
        world.playSound(npcLoc, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7f, 1.2f);
        world.spawnParticle(Particle.SMOKE, npcLoc.clone().add(0, 1.0, 0), 20, 0.3, 0.6, 0.3, 0.02);

        Player target = plugin.getServer().getPlayer(state.getTargetUuid());
        if (target != null && target.isOnline()) {
            target.sendMessage(Component.text("Officer Steve", NamedTextColor.BLUE)
                    .append(Component.text(" has clocked off. You got away with it.", NamedTextColor.GRAY)));
        }

        despawn(state);
    }

    private ItemStack ticketFor(String targetName) {
        ItemStack ticket = new ItemStack(Material.PAPER);
        ItemMeta meta = ticket.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Ticket to Jaildonia for " + targetName, NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Issued by the Jaildonia Police Department", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Wanted for: Murder", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            ticket.setItemMeta(meta);
        }
        return ticket;
    }

    /** Remove without a death: no drops, no death event. */
    private void despawn(OfficerNpcState state) {
        activeOfficers.remove(state.getMannequin().getUniqueId());
        state.getMannequin().remove();
    }

    public void removeAllOfficers() {
        for (OfficerNpcState state : activeOfficers.values()) {
            if (state.getMannequin().isValid()) {
                state.getMannequin().remove();
            }
        }
        activeOfficers.clear();
    }
}
