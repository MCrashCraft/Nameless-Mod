/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange;

import net.minecraftforge.common.ForgeConfigSpec;

/** Public configuration never stores a second copy of the website credentials. */
public final class ExchangeConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    static {
        var builder = new ForgeConfigSpec.Builder();
        ENABLED = builder.comment("Enable exchanging Just Enough Drugz dollar bills for NamelessMC Store credits.",
                        "Rate: 1 in-game dollar = 1 Store credit. Disabled by default.",
                        "Uses the private API connection from config/namelessforge/main.yaml.",
                        "You can also toggle this with /namelessexchange:exchange admin enable or disable.")
                .define("enabled", false);
        SPEC = builder.build();
    }
    private ExchangeConfig() { }
}
