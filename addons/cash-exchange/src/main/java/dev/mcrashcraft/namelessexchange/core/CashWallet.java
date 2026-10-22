/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange.core;

import java.util.List;
import java.util.UUID;

/** Minecraft methods must only be called on the server thread. Quotes contain no mutable game objects. */
public interface CashWallet {
    UUID playerId();
    boolean isCurrent();
    Quote quote(int requestedDollars);
    DebitResult debitAndPersist(Quote quote);
    void message(String text);

    record CashSlot(int slot, String itemId, int beforeCount, int takeCount) { }
    record Quote(UUID playerId, int dollars, List<CashSlot> debits) {
        public Quote { debits = List.copyOf(debits); }
    }
    enum DebitState { SAVED, NO_CHANGE, UNCERTAIN }
    record DebitResult(DebitState state, String reason) { }
}
