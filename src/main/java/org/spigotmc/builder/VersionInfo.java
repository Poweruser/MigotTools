package org.spigotmc.builder;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VersionInfo
{

    private String minecraftVersion;
    private String accessTransforms;
    private String classMappings;
    private String memberMappings;
    private String packageMappings;
}
