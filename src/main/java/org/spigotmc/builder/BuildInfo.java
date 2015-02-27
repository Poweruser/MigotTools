package org.spigotmc.builder;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BuildInfo
{

    private String name;
    private String description;
    private int toolsVersion = -1;
    private Refs refs;

    @Data
    @AllArgsConstructor
    public static class Refs
    {

        private String BuildData;
        private String Bukkit;
        private String CraftBukkit;
        private String Spigot;
    }
}
