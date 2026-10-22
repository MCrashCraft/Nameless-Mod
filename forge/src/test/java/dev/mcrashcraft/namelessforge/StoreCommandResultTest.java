/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoreCommandResultTest {
    @Test void aSuccessfulZeroResultIsExecutionNotFailure() throws Exception {
        var result = new ForgeAudienceProvider.StoreCommandResult();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.setConsumer((context, succeeded, value) -> result.completed(succeeded, value));
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("fixture").executes(context -> 0));
        assertEquals(0, dispatcher.execute("fixture", new Object()));
        assertTrue(result.succeeded());
    }

    @Test void parsingOrQueueAcceptanceDoesNotCountAsCompletion() {
        var result = new ForgeAudienceProvider.StoreCommandResult();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("fixture").executes(context -> 1));
        assertNotNull(dispatcher.parse("fixture", new Object()));
        assertFalse(result.succeeded(), "No execution callback occurred");
    }

    @Test void failedCommandExecutionDoesNotProduceASuccessfulReceipt() {
        var result = new ForgeAudienceProvider.StoreCommandResult();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.setConsumer((context, succeeded, value) -> result.completed(succeeded, value));
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("fixture").executes(context -> {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
        }));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("fixture", new Object()));
        assertFalse(result.succeeded());
    }

    @Test void mixedForkResultsRemainHeldRegardlessOfCallbackOrder() {
        var first = new ForgeAudienceProvider.StoreCommandResult();
        first.completed(true, 1);
        first.completed(false, 0);
        assertFalse(first.succeeded());
        var second = new ForgeAudienceProvider.StoreCommandResult();
        second.completed(false, 0);
        second.completed(true, 1);
        assertFalse(second.succeeded());
    }

    @Test void fulfillmentRejectsEmptyOrMultiplePhysicalCommandLines() {
        assertFalse(ForgeAudienceProvider.validStoreCommand(null));
        assertFalse(ForgeAudienceProvider.validStoreCommand(" "));
        assertFalse(ForgeAudienceProvider.validStoreCommand("say first\nsay second"));
        assertFalse(ForgeAudienceProvider.validStoreCommand("say first\rsay second"));
        assertFalse(ForgeAudienceProvider.validStoreCommand("say first\0hidden"));
        assertFalse(ForgeAudienceProvider.validStoreCommand("x".repeat(4_097)));
        assertTrue(ForgeAudienceProvider.validStoreCommand("scoreboard players set Fixture test 1"));
    }
}
