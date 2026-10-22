/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessforge;

import com.namelessmc.plugin.common.Permission;
import com.namelessmc.plugin.common.audiences.NamelessConsole;
import net.minecraft.commands.CommandSourceStack;

/** A real privileged command source; never constructed from a player or a command block. */
public final class ForgeCommandSender extends NamelessConsole {
    private final ForgeAudienceProvider audiences;
    private final boolean privileged;

    ForgeCommandSender(ForgeAudienceProvider audiences, CommandSourceStack source) {
        super(audiences.commandAudience(source));
        this.audiences = audiences;
        this.privileged = source.hasPermission(4);
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return privileged;
    }

    @Override
    public void dispatchCommand(String command) {
        if (privileged) {
            audiences.dispatchConsoleCommand(command);
        }
    }

    @Override
    public boolean dispatchStoreCommand(String command) {
        return privileged && audiences.dispatchStoreCommand(command);
    }
}
