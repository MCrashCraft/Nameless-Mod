/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded exact change using only existing bills. Never creates change or game objects. */
public final class CashPlan {
    public static final int INVENTORY_SLOTS = 36;
    public static final int MAX_STACK = 64;
    public static final int MAX_DOLLARS = INVENTORY_SLOTS * MAX_STACK * 100;
    private static final int[] VALUES = {1, 5, 10, 20, 50, 100};
    private static final Map<String, Integer> BILLS = Map.of(
            "justenoughdrugz:one_dollar_bill", 1,
            "justenoughdrugz:five_dollar_bill", 5,
            "justenoughdrugz:ten_dollar_bill", 10,
            "justenoughdrugz:twenty_dollar_bill", 20,
            "justenoughdrugz:fifty_dollar_bill", 50,
            "justenoughdrugz:hundred_dollar_bill", 100);

    private CashPlan() { }

    public record BillStack(int slot, String itemId, int count) { }

    public static int denomination(String itemId) {
        return itemId == null ? 0 : BILLS.getOrDefault(itemId, 0);
    }

    public static int availableDollars(List<BillStack> input) {
        int dollars = 0;
        for (BillStack bill : checkedStacks(input)) {
            dollars = Math.addExact(dollars, Math.multiplyExact(denomination(bill.itemId()), bill.count()));
        }
        return dollars;
    }

    public static CashWallet.Quote quote(UUID playerId, int requestedDollars, List<BillStack> input) {
        Objects.requireNonNull(playerId, "Player identity is required");
        if (requestedDollars <= 0 || requestedDollars > MAX_DOLLARS) {
            throw new IllegalArgumentException("Choose a positive whole-dollar amount within your main inventory's limit.");
        }
        List<BillStack> bills = checkedStacks(input);
        int available = availableDollars(bills);
        if (requestedDollars > available) {
            throw new IllegalArgumentException("There are not enough plain cash bills in your main inventory.");
        }
        int[] limits = new int[VALUES.length];
        for (BillStack bill : bills) limits[valueIndex(denomination(bill.itemId()))] += bill.count();

        // At each denomination, remaining[s] is the number of that bill still available
        // after making s. This bounds work to six passes over the requested amount.
        int[] remaining = new int[requestedDollars + 1];
        Arrays.fill(remaining, -1);
        remaining[0] = 0;
        BitSet[] reachable = new BitSet[VALUES.length];
        for (int type = 0; type < VALUES.length; type++) {
            int value = VALUES[type];
            BitSet stage = new BitSet(requestedDollars + 1);
            for (int amount = 0; amount <= requestedDollars; amount++) {
                if (remaining[amount] >= 0) remaining[amount] = limits[type];
                else if (amount < value || remaining[amount - value] <= 0) remaining[amount] = -1;
                else remaining[amount] = remaining[amount - value] - 1;
                if (remaining[amount] >= 0) stage.set(amount);
            }
            reachable[type] = stage;
        }
        if (!reachable[VALUES.length - 1].get(requestedDollars)) {
            throw new IllegalArgumentException("Your bills cannot make that exact amount. No change is created.");
        }
        int amount = requestedDollars;
        int[] used = new int[VALUES.length];
        for (int type = VALUES.length - 1; type >= 0; type--) {
            int maximum = Math.min(limits[type], amount / VALUES[type]);
            boolean found = false;
            for (int count = maximum; count >= 0; count--) {
                int rest = amount - count * VALUES[type];
                if (type == 0 ? rest == 0 : reachable[type - 1].get(rest)) {
                    used[type] = count;
                    amount = rest;
                    found = true;
                    break;
                }
            }
            if (!found) throw new IllegalStateException("Exact cash plan reconstruction failed");
        }
        List<CashWallet.CashSlot> debits = new ArrayList<>();
        for (BillStack bill : bills) {
            int type = valueIndex(denomination(bill.itemId()));
            int take = Math.min(bill.count(), used[type]);
            if (take > 0) {
                debits.add(new CashWallet.CashSlot(bill.slot(), bill.itemId(), bill.count(), take));
                used[type] -= take;
            }
        }
        CashWallet.Quote quote = new CashWallet.Quote(playerId, requestedDollars, debits);
        validateQuote(quote);
        return quote;
    }

    /** Quotes cross an asynchronous boundary; validate all fields again before inventory mutation. */
    public static void validateQuote(CashWallet.Quote quote) {
        if (quote == null || quote.playerId() == null || quote.dollars() <= 0
                || quote.dollars() > MAX_DOLLARS || quote.debits().isEmpty()
                || quote.debits().size() > INVENTORY_SLOTS) {
            throw new IllegalArgumentException("Invalid cash quote");
        }
        Set<Integer> slots = new HashSet<>();
        int amount = 0;
        for (CashWallet.CashSlot slot : quote.debits()) {
            if (slot == null || slot.slot() < 0 || slot.slot() >= INVENTORY_SLOTS || !slots.add(slot.slot())
                    || denomination(slot.itemId()) == 0 || slot.beforeCount() < 1 || slot.beforeCount() > MAX_STACK
                    || slot.takeCount() < 1 || slot.takeCount() > slot.beforeCount()) {
                throw new IllegalArgumentException("Invalid cash quote slot");
            }
            amount = Math.addExact(amount, Math.multiplyExact(denomination(slot.itemId()), slot.takeCount()));
        }
        if (amount != quote.dollars()) throw new IllegalArgumentException("Cash quote value does not match its bills");
    }

    public static String fingerprint(CashWallet.Quote quote) {
        validateQuote(quote);
        StringBuilder canonical = new StringBuilder("jedz-cash-v1\n").append(quote.playerId())
                .append('\n').append(quote.dollars()).append('\n');
        quote.debits().stream().sorted(Comparator.comparingInt(CashWallet.CashSlot::slot)).forEach(slot ->
                canonical.append(slot.slot()).append('|').append(slot.itemId()).append('|')
                        .append(slot.beforeCount()).append('|').append(slot.takeCount()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<BillStack> checkedStacks(List<BillStack> input) {
        if (input == null || input.size() > INVENTORY_SLOTS) throw new IllegalArgumentException("Invalid cash inventory");
        List<BillStack> sorted = new ArrayList<>(input);
        Set<Integer> slots = new HashSet<>();
        for (BillStack bill : sorted) {
            if (bill == null || bill.slot() < 0 || bill.slot() >= INVENTORY_SLOTS || !slots.add(bill.slot())
                    || denomination(bill.itemId()) == 0 || bill.count() < 1 || bill.count() > MAX_STACK) {
                throw new IllegalArgumentException("Invalid cash stack");
            }
        }
        sorted.sort(Comparator.comparingInt(BillStack::slot));
        return List.copyOf(sorted);
    }

    private static int valueIndex(int value) {
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i] == value) return i;
        throw new IllegalArgumentException("Unknown cash denomination");
    }
}
