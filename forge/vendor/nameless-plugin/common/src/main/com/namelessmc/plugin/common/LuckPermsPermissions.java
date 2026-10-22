package com.namelessmc.plugin.common;

import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.query.QueryOptions;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;
import java.util.stream.Collectors;

public class LuckPermsPermissions extends AbstractPermissions {

	private volatile @Nullable LuckPerms api;

	@Override
	public void unload() {
		this.api = null;
	}

	@Override
	public void load() {
		this.api = null;
		try {
			this.api = LuckPermsProvider.get();
		} catch (IllegalStateException | NoClassDefFoundError e) {}
	}

	@Override
	public boolean isUsable() {
		return this.api != null;
	}

	@Override
	public Set<String> getGroups() {
		final LuckPerms current = this.api;
		if (current == null) {
			throw new ProviderNotUsableException();
		}
		return current.getGroupManager().getLoadedGroups().stream()
				.map(Group::getName)
				.collect(Collectors.toUnmodifiableSet());
	}

	@Override
	public @Nullable Set<String> getPlayerGroups(NamelessPlayer player) {
		final LuckPerms current = this.api;
		if (current == null) {
			throw new ProviderNotUsableException();
		}
		final User user = current.getUserManager().getUser(player.permissionUuid());
		if (user == null) {
			return null;
		}
		// Ask LuckPerms to resolve effective inheritance, including contexts,
		// negative/expired nodes and inherited groups. Raw nodes can contain
		// a staff group that is denied or only applies in another world/server.
		final ContextManager contexts = current.getContextManager();
		final QueryOptions options = contexts.getQueryOptions(user).orElseGet(contexts::getStaticQueryOptions);
		return user.getInheritedGroups(options).stream()
				.map(Group::getName)
				.collect(Collectors.toUnmodifiableSet());
	}
}
