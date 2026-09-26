package com.zaremate.airport_security_system;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(AirportSecuritySystem.MOD_ID)
public final class AirportSecuritySystem {
    public static final String MOD_ID = "airport_security_system";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AirportSecuritySystem(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, AirportSecuritySystemConfig.SPEC);
        NeoForge.EVENT_BUS.register(AirportSecuritySystemEvents.class);
        NeoForge.EVENT_BUS.register(AirportSecuritySystemOffenses.class);
        LOGGER.info("Airport Security System loaded.");
    }
}
