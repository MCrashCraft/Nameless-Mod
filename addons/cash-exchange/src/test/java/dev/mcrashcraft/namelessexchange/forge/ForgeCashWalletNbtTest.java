/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange.forge;

import dev.mcrashcraft.namelessexchange.core.CashPlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ForgeCashWalletNbtTest {
    private static CompoundTag bill(int slot, int count) {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "justenoughdrugz:twenty_dollar_bill");
        stack.putByte("Count", (byte) count);
        stack.putByte("Slot", (byte) slot);
        return stack;
    }
    private static CompoundTag player(CompoundTag... stacks) {
        CompoundTag player = new CompoundTag();
        ListTag inventory = new ListTag();
        for (CompoundTag stack : stacks) inventory.add(stack);
        player.put("Inventory", inventory);
        return player;
    }

    @Test void plainCashCanHaveAbsentOrEmptyMetadataButNotCapabilitiesOrCustomTags() {
        CompoundTag ordinary = bill(0, 1);
        assertTrue(ForgeCashWallet.plainSerializedStack(ordinary));
        ordinary.put("tag", new CompoundTag());
        ordinary.put("ForgeCaps", new CompoundTag());
        assertTrue(ForgeCashWallet.plainSerializedStack(ordinary));
        CompoundTag capability = new CompoundTag();
        capability.putString("issuer", "untrusted");
        ordinary.put("ForgeCaps", capability);
        assertFalse(ForgeCashWallet.plainSerializedStack(ordinary));
        ordinary.remove("ForgeCaps");
        ordinary.getCompound("tag").putString("display", "Renamed cash");
        assertFalse(ForgeCashWallet.plainSerializedStack(ordinary));
    }

    @Test void savedCashComparisonUsesMainSlotsOnlyAndRetainsExactCounts() throws Exception {
        var saved = ForgeCashWallet.savedMainCash(player(bill(0, 2), bill(35, 1), bill(150, 64), bill(100, 64)));
        assertEquals(Map.of(0, new CashPlan.BillStack(0, "justenoughdrugz:twenty_dollar_bill", 2),
                35, new CashPlan.BillStack(35, "justenoughdrugz:twenty_dollar_bill", 1)), saved);
    }

    @Test void malformedSavedInventoryCannotLookLikeASuccessfullyEmptyWallet() {
        CompoundTag root = new CompoundTag();
        ListTag malformed = new ListTag();
        malformed.add(StringTag.valueOf("not an inventory stack"));
        root.put("Inventory", malformed);
        assertThrows(IOException.class, () -> ForgeCashWallet.savedMainCash(root));
        assertThrows(IOException.class, () -> ForgeCashWallet.savedMainCash(new CompoundTag()));
        assertThrows(IOException.class, () -> ForgeCashWallet.savedMainCash(player(bill(0, 1), bill(0, 1))));
        assertThrows(IOException.class, () -> ForgeCashWallet.savedMainCash(player(bill(0, 65))));
        CompoundTag tagged = bill(0, 1);
        tagged.putString("ForgeCaps", "not a compound");
        assertThrows(IOException.class, () -> ForgeCashWallet.savedMainCash(player(tagged)));
    }
}
