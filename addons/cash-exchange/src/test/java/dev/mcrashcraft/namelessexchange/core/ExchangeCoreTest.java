/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeCoreTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void savedCashAndPostingReceiptPrecedeSingleIntegerCredit() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            assertTrue(f.service.begin(f.wallet, f.quote()));
            assertEquals(0, f.wallet.debits);
            f.worker.one(); f.main.one();
            assertEquals(1, f.wallet.debits);
            assertEquals(ExchangeJournal.State.DEBITED, f.state());
            assertEquals(0, f.posts.get());
            f.worker.one(); f.main.all();
            assertEquals(1, f.posts.get());
            assertEquals("{\"credits\":10}", f.lastBody.get());
            assertEquals(ExchangeJournal.State.CREDITED, f.state());
            assertTrue(f.logs.stream().noneMatch(s -> s.contains("synthetic-key") || s.contains("FixtureBuyer")));
        }
    }

    @Test void disabledAddonDoesNotLookUpOrDebit() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            f.enabled.set(false);
            assertFalse(f.service.begin(f.wallet, f.quote()));
            assertEquals(0, f.gets.get()); assertEquals(0, f.wallet.debits); assertFalse(Files.exists(f.journal.file()));
        }
    }

    @Test void unverifiedOrWrongUuidOrDuplicateLinkNeverTouchesCash() throws Exception {
        for (String mode : List.of("unverified", "wrong-uuid", "duplicate", "inactive", "banned")) {
            try (Fixture f = new Fixture(temp.resolve(mode))) {
                f.linkMode = mode;
                assertTrue(f.service.begin(f.wallet, f.quote()));
                f.worker.one(); f.main.all();
                assertEquals(0, f.wallet.debits, mode); assertEquals(0, f.posts.get(), mode);
                assertFalse(Files.exists(f.journal.file()), mode);
            }
        }
    }

    @Test void duplicateWhilePreflightIsQueuedIsRejected() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            assertTrue(f.service.begin(f.wallet, f.quote()));
            assertFalse(f.service.begin(f.wallet, f.quote()));
            f.complete();
            assertEquals(1, f.gets.get()); assertEquals(1, f.wallet.debits); assertEquals(1, f.posts.get());
        }
    }

    @Test void changedInventoryCancelsWithoutPosting() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            f.wallet.result = CashWallet.DebitState.NO_CHANGE;
            assertTrue(f.service.begin(f.wallet, f.quote())); f.complete();
            assertEquals(ExchangeJournal.State.CANCELLED, f.state()); assertEquals(0, f.posts.get());
        }
    }

    @Test void uncertainNativeSaveIsHeldWithoutPosting() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            f.wallet.result = CashWallet.DebitState.UNCERTAIN;
            assertTrue(f.service.begin(f.wallet, f.quote())); f.complete();
            assertEquals(ExchangeJournal.State.HELD, f.state()); assertEquals(0, f.posts.get());
            assertEquals(1, f.service.heldCount());
        }
    }

    @Test void lostResponseIsNotRetriedAndRestartBlocksReplay() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            f.dropResponse = true;
            assertTrue(f.service.begin(f.wallet, f.quote())); f.complete();
            assertEquals(1, f.posts.get(), "A non-idempotent POST must not be replayed after a lost response");
            assertEquals(ExchangeJournal.State.HELD, f.state());
            ExchangeService reopened = f.service(new ExchangeJournal(temp), () -> f.client);
            assertFalse(reopened.begin(f.wallet, f.quote()));
            assertEquals(1, f.posts.get()); assertEquals(1, f.wallet.debits);
        }
    }

    @Test void genericJsonOrRedirectIsNotProofOfCredit() throws Exception {
        for (int status : List.of(200, 302, 503)) {
            try (Fixture f = new Fixture(temp.resolve("http-" + status))) {
                f.postStatus = status; f.response = "{}";
                assertTrue(f.service.begin(f.wallet, f.quote())); f.complete();
                assertEquals(ExchangeJournal.State.HELD, f.state()); assertEquals(1, f.posts.get());
            }
        }
    }

    @Test void slowTrickleResponseHasABoundedTotalBudgetAndNoReplay() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            f.slowResponse = true;
            ExchangeApiClient shortBudget = new ExchangeApiClient("http://127.0.0.1:" + f.server.getAddress().getPort() + "/api/v2", "synthetic-key", 200);
            ExchangeService service = f.service(f.journal, () -> shortBudget);
            assertTrue(service.begin(f.wallet, f.quote()));
            long start = System.nanoTime();
            f.complete();
            assertTrue(System.nanoTime() - start < 2_000_000_000L);
            assertEquals(ExchangeJournal.State.HELD, f.state()); assertEquals(1, f.posts.get());
        }
    }

    @Test void disablingBeforeDebitCancelsButAfterSavedDebitStillRecordsConfirmedOutcome() throws Exception {
        try (Fixture f = new Fixture(temp.resolve("before"))) {
            assertTrue(f.service.begin(f.wallet, f.quote())); f.worker.one(); f.enabled.set(false); f.main.all();
            assertEquals(0, f.wallet.debits); assertEquals(0, f.posts.get()); assertFalse(Files.exists(f.journal.file()));
        }
        try (Fixture f = new Fixture(temp.resolve("after"))) {
            assertTrue(f.service.begin(f.wallet, f.quote())); f.worker.one(); f.main.one(); f.enabled.set(false);
            f.worker.one(); f.main.all();
            assertEquals(ExchangeJournal.State.CREDITED, f.state()); assertEquals(1, f.posts.get());
        }
    }

    @Test void apiReplacementAfterSavedDebitKeepsCapturedClientForCredit() throws Exception {
        try (Fixture first = new Fixture(temp.resolve("first")); Fixture second = new Fixture(temp.resolve("second"))) {
            AtomicReference<ExchangeApiClient> selected = new AtomicReference<>(first.client);
            ExchangeService service = first.service(first.journal, selected::get);
            assertTrue(service.begin(first.wallet, first.quote()));
            first.worker.one(); first.main.one(); selected.set(second.client); service.invalidatePreflights(); first.worker.one(); first.main.all();
            assertEquals(1, first.posts.get()); assertEquals(0, second.posts.get());
        }
    }

    @Test void apiReplacementBeforeDebitCancelsTheOldVerifiedPreflight() throws Exception {
        try (Fixture first = new Fixture(temp.resolve("first")); Fixture second = new Fixture(temp.resolve("second"))) {
            AtomicReference<ExchangeApiClient> selected = new AtomicReference<>(first.client);
            ExchangeService service = first.service(first.journal, selected::get);
            assertTrue(service.begin(first.wallet, first.quote()));
            first.worker.one(); selected.set(second.client); first.main.all(); first.worker.all();
            assertEquals(0, first.wallet.debits); assertEquals(0, first.posts.get()); assertEquals(0, second.posts.get());
            assertFalse(Files.exists(first.journal.file()));
        }
    }

    @Test void invalidatingAnOffOnToggleCancelsPreflightEvenWithTheSameClient() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            assertTrue(f.service.begin(f.wallet, f.quote())); f.worker.one();
            f.enabled.set(false); f.service.invalidatePreflights(); f.enabled.set(true);
            f.main.all(); f.worker.all();
            assertEquals(0, f.wallet.debits); assertEquals(0, f.posts.get()); assertFalse(Files.exists(f.journal.file()));
        }
    }

    @Test void workerRejectionAfterSavedDebitIsHeldAndBeforeDebitLeavesCash() throws Exception {
        try (Fixture f = new Fixture(temp.resolve("before"))) {
            f.worker.reject = true;
            assertFalse(f.service.begin(f.wallet, f.quote())); f.main.all();
            assertEquals(0, f.wallet.debits); assertFalse(Files.exists(f.journal.file()));
        }
        try (Fixture f = new Fixture(temp.resolve("after"))) {
            assertTrue(f.service.begin(f.wallet, f.quote())); f.worker.one(); f.worker.reject = true; f.main.all();
            assertEquals(ExchangeJournal.State.HELD, f.state()); assertEquals(1, f.wallet.debits); assertEquals(0, f.posts.get());
        }
    }

    @Test void malformedJournalBeforeDebitStopsWithoutChangingInventory() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            assertTrue(f.service.begin(f.wallet, f.quote())); f.worker.one(); Files.writeString(f.journal.file(), "invalid"); f.main.all();
            assertEquals(0, f.wallet.debits); assertEquals(0, f.posts.get());
        }
    }

    @Test void restartHoldsEveryUnresolvedPhaseAndPreservesCompletedReceipt() throws Exception {
        ExchangeJournal journal = new ExchangeJournal(temp);
        for (int i = 0; i < 4; i++) {
            UUID player = new UUID(0, i + 10);
            ExchangeJournal.Entry entry = journal.prepare(quote(player), 123);
            if (i >= 1) journal.transition(entry.id(), ExchangeJournal.State.DEBITED);
            if (i >= 2) journal.transition(entry.id(), ExchangeJournal.State.POSTING);
            if (i == 3) journal.transition(entry.id(), ExchangeJournal.State.CREDITED);
        }
        ExchangeJournal reopened = new ExchangeJournal(temp);
        assertEquals(3, reopened.heldCount());
        for (int i = 0; i < 3; i++) {
            UUID heldPlayer = new UUID(0, i + 10);
            assertThrows(IOException.class, () -> reopened.prepare(quote(heldPlayer), 123));
        }
        assertEquals(ExchangeJournal.State.CREDITED, reopened.latest(new UUID(0, 13)).orElseThrow().state());
    }

    @Test void malformedAndOversizedJournalsFailClosed() throws Exception {
        Files.createDirectories(temp);
        Path file = temp.resolve("exchange-journal.json");
        Files.writeString(file, "{}"); assertThrows(IOException.class, () -> new ExchangeJournal(temp));
        Files.write(file, new byte[8 * 1024 * 1024 + 1]); assertThrows(IOException.class, () -> new ExchangeJournal(temp));
    }

    @Test void fractionalJournalNumbersAndUnbackedQuotesAreRejected() throws Exception {
        ExchangeJournal journal = new ExchangeJournal(temp);
        ExchangeJournal.Entry receipt = journal.prepare(quote(PLAYER), 123);
        JsonObject raw = JsonParser.parseString(Files.readString(journal.file())).getAsJsonObject();
        raw.getAsJsonObject("entries").getAsJsonObject(receipt.id().toString()).addProperty("dollars", 10.5);
        Files.writeString(journal.file(), raw.toString());
        assertThrows(IOException.class, () -> new ExchangeJournal(temp));
        assertThrows(IOException.class, () -> ExchangeJournal.validateQuote(new CashWallet.Quote(PLAYER, 100,
                List.of(new CashWallet.CashSlot(0, "justenoughdrugz:ten_dollar_bill", 1, 1)))));
        assertThrows(IOException.class, () -> ExchangeJournal.validateQuote(new CashWallet.Quote(PLAYER, 10,
                List.of(new CashWallet.CashSlot(0, "minecraft:paper", 1, 1)))));
    }

    @Test void transportRejectsInsecureRemoteAndCredentialInUrl() {
        assertThrows(IllegalArgumentException.class, () -> new ExchangeApiClient("http://example.com/api/v2", "key"));
        assertThrows(IllegalArgumentException.class, () -> new ExchangeApiClient("https://key@example.com/api/v2", "key"));
        assertThrows(IllegalArgumentException.class, () -> new ExchangeApiClient("https://example.com/api/v2", "key\r\nOther: header"));
    }

    private static CashWallet.Quote quote(UUID player) {
        return new CashWallet.Quote(player, 10, List.of(new CashWallet.CashSlot(0, "justenoughdrugz:ten_dollar_bill", 1, 1)));
    }

    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final AtomicBoolean main;
        final boolean kind;
        boolean reject;
        Queue(AtomicBoolean main, boolean kind) { this.main = main; this.kind = kind; }
        @Override public void execute(Runnable command) { if (reject) throw new RejectedExecutionException(); tasks.add(command); }
        void one() { Runnable task = tasks.remove(); boolean previous = main.getAndSet(kind); try { task.run(); } finally { main.set(previous); } }
        void all() { while (!tasks.isEmpty()) one(); }
    }
    private static final class Wallet implements CashWallet {
        final Fixture f;
        int debits;
        volatile boolean saved;
        DebitState result = DebitState.SAVED;
        Wallet(Fixture f) { this.f = f; }
        void checkMain() { assertTrue(f.onMain.get(), "Native wallet touched by worker"); }
        @Override public UUID playerId() { checkMain(); return PLAYER; }
        @Override public boolean isCurrent() { checkMain(); return true; }
        @Override public Quote quote(int requestedDollars) { checkMain(); return f.quote(); }
        @Override public DebitResult debitAndPersist(Quote quote) {
            checkMain();
            try { assertEquals(ExchangeJournal.State.PREPARED, f.state()); } catch (IOException e) { throw new AssertionError(e); }
            debits++; saved = result == DebitState.SAVED;
            return new DebitResult(result, "synthetic fixture");
        }
        @Override public void message(String text) { checkMain(); }
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicBoolean onMain = new AtomicBoolean(true);
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final Queue main = new Queue(onMain, true);
        final Queue worker = new Queue(onMain, false);
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger posts = new AtomicInteger();
        final AtomicReference<String> lastBody = new AtomicReference<>();
        final List<String> logs = new ArrayList<>();
        final ExchangeJournal journal;
        final Wallet wallet = new Wallet(this);
        final HttpServer server;
        final ExchangeApiClient client;
        final ExchangeService service;
        volatile String linkMode = "valid";
        volatile boolean dropResponse;
        volatile boolean slowResponse;
        volatile int postStatus = 200;
        volatile String response = "{\"message\":\"Successfully added credits to user\"}";
        Fixture(Path data) throws IOException {
            journal = new ExchangeJournal(data);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v2/", exchange -> {
                try {
                    assertEquals("Bearer synthetic-key", exchange.getRequestHeaders().getFirst("Authorization"));
                    if (exchange.getRequestMethod().equals("GET")) {
                        gets.incrementAndGet();
                        assertTrue(exchange.getRequestURI().getRawPath().endsWith("integration_id%3AMinecraft%3A" + PLAYER.toString().replace("-", "")));
                        byte[] body = account().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                    } else {
                        posts.incrementAndGet();
                        assertTrue(wallet.saved, "HTTP credit preceded saved debit");
                        assertEquals(ExchangeJournal.State.POSTING, state(), "HTTP credit preceded durable POSTING");
                        assertTrue(exchange.getRequestURI().getPath().endsWith("users/id:123/add-credits"));
                        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        JsonObject request = JsonParser.parseString(lastBody.get()).getAsJsonObject();
                        assertTrue(request.get("credits").getAsJsonPrimitive().isNumber());
                        if (!dropResponse) {
                            byte[] body = response.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Location", "/must-not-follow");
                            exchange.sendResponseHeaders(postStatus, body.length);
                            if (slowResponse) {
                                for (byte one : body) {
                                    exchange.getResponseBody().write(one); exchange.getResponseBody().flush();
                                    try { Thread.sleep(30); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                                }
                            } else exchange.getResponseBody().write(body);
                        }
                    }
                } finally { exchange.close(); }
            });
            server.start();
            client = new ExchangeApiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2", "synthetic-key");
            service = service(journal, () -> client);
        }
        ExchangeService service(ExchangeJournal source, java.util.function.Supplier<ExchangeApiClient> selected) {
            return new ExchangeService(source, selected, main, worker, enabled::get, logs::add);
        }
        CashWallet.Quote quote() { return ExchangeCoreTest.quote(PLAYER); }
        ExchangeJournal.State state() throws IOException { return journal.latest(PLAYER).orElseThrow().state(); }
        void complete() { worker.all(); main.all(); worker.all(); main.all(); }
        String account() {
            String id = linkMode.equals("wrong-uuid") ? "00000000000000000000000000000002" : PLAYER.toString().replace("-", "");
            String integration = "{\"integration\":\"Minecraft\",\"identifier\":\"" + id + "\",\"username\":\"FixtureBuyer\",\"verified\":" + !linkMode.equals("unverified") + "}";
            return "{\"exists\":true,\"id\":123,\"validated\":" + !linkMode.equals("inactive") + ",\"banned\":" + linkMode.equals("banned")
                    + ",\"integrations\":[" + integration + (linkMode.equals("duplicate") ? "," + integration : "") + "]}";
        }
        @Override public void close() { server.stop(0); }
    }
}
