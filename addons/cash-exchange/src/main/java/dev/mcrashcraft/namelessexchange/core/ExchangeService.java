/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange.core;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Cash changes are scheduled on the server thread; website traffic runs on the addon's persistent worker. */
public final class ExchangeService {
    private static final long COOLDOWN_NANOS = 10_000_000_000L;
    private final ExchangeJournal journal;
    private final Supplier<ExchangeApiClient> api;
    private final Executor mainThread;
    private final Executor worker;
    private final BooleanSupplier enabled;
    private final Consumer<String> sanitizedLog;
    private final ConcurrentHashMap<UUID, Operation> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private final AtomicLong preflightGeneration = new AtomicLong();
    private volatile boolean journalUnavailable;

    private static final class Operation {
        final CashWallet wallet;
        final CashWallet.Quote quote;
        final ExchangeApiClient client;
        final long beginGeneration;
        ExchangeJournal.Entry receipt;
        Operation(CashWallet wallet, CashWallet.Quote quote, ExchangeApiClient client, long beginGeneration) {
            this.wallet = wallet; this.quote = quote; this.client = client; this.beginGeneration = beginGeneration;
        }
    }

    public ExchangeService(ExchangeJournal journal, ExchangeApiClient api, Executor mainThread, Executor worker,
                           BooleanSupplier enabled, Consumer<String> sanitizedLog) {
        this(journal, () -> api, mainThread, worker, enabled, sanitizedLog);
    }
    public ExchangeService(ExchangeJournal journal, Supplier<ExchangeApiClient> api, Executor mainThread, Executor worker,
                           BooleanSupplier enabled, Consumer<String> sanitizedLog) {
        this.journal = Objects.requireNonNull(journal);
        this.api = Objects.requireNonNull(api);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.worker = Objects.requireNonNull(worker);
        this.enabled = Objects.requireNonNull(enabled);
        this.sanitizedLog = Objects.requireNonNull(sanitizedLog);
    }

    /** Called by the server thread with an immutable native quote. Returns true only when preflight was queued. */
    public boolean begin(CashWallet wallet, CashWallet.Quote quote) {
        Objects.requireNonNull(wallet);
        try {
            ExchangeJournal.validateQuote(quote);
            if (!wallet.playerId().equals(quote.playerId()) || !wallet.isCurrent()) { wallet.message("Exchange cancelled: player or inventory session changed."); return false; }
            if (!enabled.getAsBoolean()) { wallet.message("Cash exchange is disabled."); return false; }
            if (journalUnavailable) { wallet.message("Cash exchange is unavailable pending operator review."); return false; }
            if (inFlight.containsKey(quote.playerId()) || journal.unresolved(quote.playerId()).isPresent()) {
                wallet.message("You already have an exchange pending or held for review. Use the exchange status command."); return false;
            }
            long now = System.nanoTime();
            cooldown.entrySet().removeIf(entry -> now - entry.getValue() >= COOLDOWN_NANOS);
            if (cooldown.containsKey(quote.playerId()) || cooldown.size() >= 10_000) { wallet.message("Please wait a few seconds before another exchange."); return false; }
            ExchangeApiClient client = Objects.requireNonNull(api.get());
            Operation op = new Operation(wallet, quote, client, preflightGeneration.get());
            if (inFlight.putIfAbsent(quote.playerId(), op) != null) { wallet.message("An exchange is already pending."); return false; }
            try { worker.execute(() -> verify(op)); }
            catch (RuntimeException rejected) { finish(op, "Exchange could not start. Your cash was not changed."); return false; }
            wallet.message("Checking your verified website account before exchanging $" + quote.dollars() + ".");
            return true;
        } catch (IOException invalid) {
            wallet.message("Cash exchange could not start; your cash was not changed.");
            log("Cash exchange preflight or journal check failed; cash unchanged.");
            return false;
        } catch (RuntimeException invalid) {
            wallet.message("Cash exchange is unavailable; your cash was not changed.");
            log("Cash exchange configuration or quote was rejected; cash unchanged.");
            return false;
        }
    }

    private void verify(Operation op) {
        final ExchangeApiClient.Account account;
        try { account = op.client.verify(op.quote.playerId()); }
        catch (IOException | RuntimeException failure) { finish(op, "Exchange stopped: a verified, active website account linked to your Minecraft UUID is required. Your cash was not changed."); return; }
        try { mainThread.execute(() -> debit(op, account)); }
        catch (RuntimeException stopped) { finish(op, "Exchange cancelled before cash debit."); }
    }

    private void debit(Operation op, ExchangeApiClient.Account account) {
        if (inFlight.get(op.quote.playerId()) != op) return;
        boolean valid;
        try {
            valid = enabled.getAsBoolean() && op.wallet.isCurrent()
                    && op.beginGeneration == preflightGeneration.get() && api.get() == op.client;
        } catch (RuntimeException changed) { valid = false; }
        if (!valid) { finish(op, "Exchange cancelled before cash debit. Your cash was not changed."); return; }
        try { op.receipt = journal.prepare(op.quote, account.userId()); }
        catch (IOException | RuntimeException failure) {
            journalUnavailable = true;
            log("Exchange receipt preparation failed; no cash debit was attempted.");
            finish(op, "Exchange stopped before cash debit; an operator must check the journal."); return;
        }
        final CashWallet.DebitResult debit;
        try { debit = Objects.requireNonNull(op.wallet.debitAndPersist(op.quote)); }
        catch (RuntimeException uncertain) { hold(op); return; }
        if (debit.state() == CashWallet.DebitState.NO_CHANGE) {
            try { journal.transition(op.receipt.id(), ExchangeJournal.State.CANCELLED); }
            catch (IOException | RuntimeException failure) { hold(op); return; }
            finish(op, "Exchange cancelled: the cash inventory changed or could not be used. No cash was taken."); return;
        }
        if (debit.state() != CashWallet.DebitState.SAVED) { hold(op); return; }
        try { journal.transition(op.receipt.id(), ExchangeJournal.State.DEBITED); }
        catch (IOException | RuntimeException failure) { hold(op); return; }
        try { worker.execute(() -> submit(op, account)); }
        catch (RuntimeException rejected) { hold(op); }
    }

    private void submit(Operation op, ExchangeApiClient.Account account) {
        try {
            journal.transition(op.receipt.id(), ExchangeJournal.State.POSTING);
            op.client.credit(account, op.quote.dollars());
            journal.transition(op.receipt.id(), ExchangeJournal.State.CREDITED);
        } catch (IOException | RuntimeException uncertain) { hold(op); return; }
        log("Exchange " + op.receipt.id() + " CREDITED; dollars=" + op.quote.dollars() + ".");
        finish(op, "Exchanged $" + op.quote.dollars() + " for " + op.quote.dollars() + " Store credits. Receipt: " + op.receipt.id());
    }

    private void hold(Operation op) {
        if (op.receipt != null) {
            try { journal.transition(op.receipt.id(), ExchangeJournal.State.HELD); }
            catch (IOException | RuntimeException unavailable) { journalUnavailable = true; }
            log("Exchange " + op.receipt.id() + " HELD for operator review; no automatic retry or refund.");
        }
        finish(op, "Exchange held for operator review. No automatic retry or refund will occur. Receipt: " + (op.receipt == null ? "unavailable" : op.receipt.id()));
    }

    private void finish(Operation op, String message) {
        inFlight.remove(op.quote.playerId(), op);
        cooldown.put(op.quote.playerId(), System.nanoTime());
        try { mainThread.execute(() -> { if (op.wallet.isCurrent()) op.wallet.message(message); }); }
        catch (RuntimeException stopped) { /* Outcome is durable even when the server cannot deliver chat. */ }
    }
    private void log(String message) { try { sanitizedLog.accept(message); } catch (RuntimeException ignored) { } }

    /** Call on reload or toggle changes. Already-saved debits must still finish with their captured client. */
    public void invalidatePreflights() { preflightGeneration.incrementAndGet(); }

    /** Called on the server thread. No account data, commands or authentication is displayed. */
    public void status(CashWallet wallet) {
        if (inFlight.containsKey(wallet.playerId())) { wallet.message("Your cash exchange is in progress."); return; }
        try {
            var current = journal.unresolved(wallet.playerId());
            var latest = current.isPresent() ? current : journal.latest(wallet.playerId());
            wallet.message(latest.map(e -> "Exchange " + e.id() + ": $" + e.dollars() + ", " + e.state() + "."
                    + (e.unresolved() ? " Operator review required; no automatic retry or refund." : "")).orElse("You have no recorded cash exchanges."));
        } catch (IOException | RuntimeException failure) {
            journalUnavailable = true;
            wallet.message("Exchange journal unavailable; contact an operator.");
        }
    }
    public int heldCount() {
        try { return journal.heldCount(); }
        catch (IOException | RuntimeException failure) { journalUnavailable = true; log("Exchange journal unavailable; new exchanges are blocked."); return -1; }
    }
}
