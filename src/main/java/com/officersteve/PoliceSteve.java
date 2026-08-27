package com.officersteve;

import org.bukkit.plugin.java.JavaPlugin;

public class PoliceSteve extends JavaPlugin {

    private PoliceListener policeListener;

    @Override
    public void onEnable() {
        policeListener = new PoliceListener(this);
        getServer().getPluginManager().registerEvents(policeListener, this);

        // Ticks the chase/attack behavior for every active Officer Steve, ~5 times a second
        getServer().getScheduler().runTaskTimer(this, policeListener::tickOfficers, 4L, 4L);

        getLogger().info("PoliceSteve enabled — justice will be served.");
    }

    @Override
    public void onDisable() {
        if (policeListener != null) {
            policeListener.removeAllOfficers();
        }
        getLogger().info("PoliceSteve disabled.");
    }
}
