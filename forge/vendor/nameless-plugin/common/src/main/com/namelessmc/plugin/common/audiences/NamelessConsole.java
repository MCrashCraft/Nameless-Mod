package com.namelessmc.plugin.common.audiences;

import com.namelessmc.plugin.common.Permission;
import net.kyori.adventure.audience.Audience;

public abstract class NamelessConsole extends NamelessCommandSender {

	public NamelessConsole(final Audience audience) {
		super(audience);
	}

	public abstract void dispatchCommand(String command);

	/**
	 * Execute synchronously on the native server thread and report actual command
	 * success. Store delivery must fail closed on platforms without this contract.
	 */
	public boolean dispatchStoreCommand(String command) {
		throw new UnsupportedOperationException("Verified Store command execution is not supported by this platform");
	}

	@Override
	public boolean hasPermission(Permission permission) {
		return true;
	}

}
