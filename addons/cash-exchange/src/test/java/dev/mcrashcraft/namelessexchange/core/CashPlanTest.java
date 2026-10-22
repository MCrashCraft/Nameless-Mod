/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CashPlanTest {
    private static final UUID PLAYER = UUID.fromString("67b1dc53-3181-4409-97c0-35ce93520f95");
    private static CashPlan.BillStack bill(int slot, String name, int count) {
        return new CashPlan.BillStack(slot, "justenoughdrugz:" + name + "_dollar_bill", count);
    }

    @Test void exactBoundedSelectionDoesNotFallIntoGreedyTrap() {
        var input = List.of(bill(0, "fifty", 1), bill(1, "twenty", 3));
        var quote = CashPlan.quote(PLAYER, 60, input);
        assertEquals(List.of(new CashWallet.CashSlot(1, "justenoughdrugz:twenty_dollar_bill", 3, 3)), quote.debits());
        assertEquals(110, CashPlan.availableDollars(input));
    }

    @Test void cannotMakeChangeOrMutateTheSuppliedInventory() {
        var input = new ArrayList<>(List.of(bill(0, "hundred", 2)));
        var snapshot = List.copyOf(input);
        assertThrows(IllegalArgumentException.class, () -> CashPlan.quote(PLAYER, 60, input));
        assertEquals(snapshot, input);
        assertThrows(IllegalArgumentException.class, () -> CashPlan.quote(PLAYER, 201, input));
    }

    @Test void quotePreservesOriginalCountsForSelectedSlotsAndIsImmutable() {
        var input = new ArrayList<>(List.of(bill(4, "five", 20), bill(0, "one", 5)));
        var quote = CashPlan.quote(PLAYER, 11, input);
        assertEquals(List.of(new CashWallet.CashSlot(0, "justenoughdrugz:one_dollar_bill", 5, 1),
                new CashWallet.CashSlot(4, "justenoughdrugz:five_dollar_bill", 20, 2)), quote.debits());
        input.clear();
        assertEquals(2, quote.debits().size());
        assertThrows(UnsupportedOperationException.class, () -> quote.debits().clear());
    }

    @Test void allSixDenominationsHaveTheRequestedValuesAndFullInventoryIsBounded() {
        var input = List.of(bill(0, "one", 1), bill(1, "five", 1), bill(2, "ten", 1),
                bill(3, "twenty", 1), bill(4, "fifty", 1), bill(5, "hundred", 1));
        assertEquals(186, CashPlan.availableDollars(input));
        assertEquals(6, CashPlan.quote(PLAYER, 186, input).debits().size());
        List<CashPlan.BillStack> maximum = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) maximum.add(bill(slot, "hundred", 64));
        var quote = CashPlan.quote(PLAYER, CashPlan.MAX_DOLLARS, maximum);
        assertEquals(230_400, quote.dollars());
        assertEquals(36, quote.debits().size());
        assertTrue(quote.debits().stream().allMatch(slot -> slot.beforeCount() == 64 && slot.takeCount() == 64));
    }

    @Test void rejectsInventedCurrencyOverstacksInvalidSlotsAndDuplicateSlots() {
        assertEquals(0, CashPlan.denomination("minecraft:paper"));
        assertThrows(IllegalArgumentException.class, () -> CashPlan.quote(PLAYER, 1,
                List.of(new CashPlan.BillStack(0, "minecraft:paper", 1))));
        assertThrows(IllegalArgumentException.class, () -> CashPlan.quote(PLAYER, 1, List.of(bill(0, "one", 65))));
        assertThrows(IllegalArgumentException.class, () -> CashPlan.quote(PLAYER, 1, List.of(bill(36, "one", 1))));
        assertThrows(IllegalArgumentException.class, () -> CashPlan.quote(PLAYER, 1,
                List.of(bill(0, "one", 1), bill(0, "five", 1))));
        assertThrows(IllegalArgumentException.class, () -> CashPlan.quote(PLAYER, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> CashPlan.quote(PLAYER, Integer.MAX_VALUE, List.of()));
    }

    @Test void reconstructedQuoteMustStillMatchItsExactValueAndCounts() {
        var badValue = new CashWallet.Quote(PLAYER, 100,
                List.of(new CashWallet.CashSlot(0, "justenoughdrugz:one_dollar_bill", 1, 1)));
        var tooMany = new CashWallet.Quote(PLAYER, 2,
                List.of(new CashWallet.CashSlot(0, "justenoughdrugz:one_dollar_bill", 1, 2)));
        assertThrows(IllegalArgumentException.class, () -> CashPlan.validateQuote(badValue));
        assertThrows(IllegalArgumentException.class, () -> CashPlan.validateQuote(tooMany));
    }

    @Test void fingerprintBindsPlayerAmountAndQuotedSlotCounts() {
        var quote = CashPlan.quote(PLAYER, 10, List.of(bill(0, "ten", 2)));
        assertEquals(64, CashPlan.fingerprint(quote).length());
        assertEquals(CashPlan.fingerprint(quote), CashPlan.fingerprint(CashPlan.quote(PLAYER, 10, List.of(bill(0, "ten", 2)))));
        assertNotEquals(CashPlan.fingerprint(quote), CashPlan.fingerprint(CashPlan.quote(PLAYER, 10, List.of(bill(0, "ten", 3)))));
        assertNotEquals(CashPlan.fingerprint(quote), CashPlan.fingerprint(CashPlan.quote(UUID.randomUUID(), 10, List.of(bill(0, "ten", 2)))));
    }
}
