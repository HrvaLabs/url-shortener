package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.client.CloudflareHostname;
import cc.hrva.urlshortener.client.CloudflareResult;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

final class FakeCloudflare extends CloudflareClient {

    static final String NOT_CNAMED = "custom hostname does not CNAME to this zone.";
    private static final int PAGE_SIZE = 50;

    enum Call {
        FIND,
        CREATE,
        GET,
        DELETE,
        LIST,
        QUOTA
    }

    private final Map<String, CloudflareHostname> hostnames = new LinkedHashMap<>();
    private final Map<Call, Deque<Object>> scripts = new EnumMap<>(Call.class);
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> callsInsideATransaction = new CopyOnWriteArrayList<>();
    private final List<String> callsHoldingAConnection = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile CloudflareResult<?> everyCreate;
    private volatile Runnable duringCreate;
    private volatile Call blocked;
    private volatile CountDownLatch blockedEntered;
    private volatile CountDownLatch blockedReleased;
    private volatile CloudflareResult<CloudflareHostname> afterLostCreate;
    private volatile IntSupplier connectionsInUse;

    FakeCloudflare() {
        super(new AppProperties(), JsonMapper.builder().build());
    }

    synchronized void reset() {
        hostnames.clear();
        scripts.clear();
        calls.clear();
        callsInsideATransaction.clear();
        callsHoldingAConnection.clear();
        connectionsInUse = null;
        ids.set(0);
        everyCreate = null;
        duringCreate = null;
        blocked = null;
        blockedEntered = null;
        blockedReleased = null;
        afterLostCreate = null;
    }

    synchronized String store(final String hostname, final String status, final String sslStatus) {
        final var id = "cf-" + ids.incrementAndGet();
        hostnames.put(id, new CloudflareHostname(id, hostname, status, sslStatus, List.of(), List.of()));
        return id;
    }

    synchronized void state(final String id, final String status, final String sslStatus, final List<String> verificationErrors,
                            final List<String> validationErrors) {
        final var current = hostnames.get(id);
        hostnames.put(id, new CloudflareHostname(id, current.hostname(), status, sslStatus, verificationErrors, validationErrors));
    }

    synchronized void state(final String id, final String status, final String sslStatus) {
        state(id, status, sslStatus, List.of(), List.of());
    }

    synchronized boolean has(final String id) {
        return hostnames.containsKey(id);
    }

    synchronized void script(final Call call, final CloudflareResult<?> result) {
        scripts.computeIfAbsent(call, ignored -> new ArrayDeque<>()).add(result);
    }

    void failEveryCreate(final CloudflareResult<?> result) {
        everyCreate = result;
    }

    void storeTheNextCreateButLoseTheAnswer() {
        afterLostCreate = new CloudflareResult.Unreachable<>("timeout");
    }

    void duringTheNextCreate(final Runnable action) {
        duringCreate = action;
    }

    void blockTheNext(final Call call, final CountDownLatch entered, final CountDownLatch released) {
        blockedEntered = entered;
        blockedReleased = released;
        blocked = call;
    }

    void watchConnections(final IntSupplier inUse) {
        connectionsInUse = inUse;
    }

    List<String> callsHoldingAConnection() {
        return List.copyOf(callsHoldingAConnection);
    }

    List<String> calls() {
        return List.copyOf(calls);
    }

    long count(final Call call) {
        return calls.stream().filter(entry -> entry.startsWith(call.name() + " ")).count();
    }

    List<String> callsInsideATransaction() {
        return List.copyOf(callsInsideATransaction);
    }

    @Override
    public CloudflareResult<Optional<CloudflareHostname>> findByHostname(final String hostname) {
        record(Call.FIND, hostname);
        return this.<Optional<CloudflareHostname>>scripted(Call.FIND).orElseGet(() -> {
            synchronized (this) {
                return new CloudflareResult.Ok<>(hostnames.values().stream().filter(found -> found.hostname().equals(hostname)).findFirst(),
                        hostnames.size());
            }
        });
    }

    @Override
    public CloudflareResult<CloudflareHostname> create(final String hostname) {
        record(Call.CREATE, hostname);
        final var action = duringCreate;
        duringCreate = null;
        if (action != null) {
            action.run();
        }
        final var scripted = this.<CloudflareHostname>scripted(Call.CREATE);
        if (scripted.isPresent()) {
            return scripted.get();
        }
        if (everyCreate != null) {
            return cast(everyCreate);
        }
        synchronized (this) {
            if (hostnames.values().stream().anyMatch(found -> found.hostname().equals(hostname))) {
                return new CloudflareResult.Refused<>(409, 1406, "Duplicate custom hostname found.");
            }
            final var id = "cf-" + ids.incrementAndGet();
            final var created = new CloudflareHostname(id, hostname, "pending", "pending_validation", List.of(NOT_CNAMED), List.of());
            hostnames.put(id, created);
            final var lost = afterLostCreate;
            afterLostCreate = null;
            return lost != null ? lost : new CloudflareResult.Ok<>(created, null);
        }
    }

    @Override
    public CloudflareResult<CloudflareHostname> get(final String id) {
        record(Call.GET, id);
        return this.<CloudflareHostname>scripted(Call.GET).orElseGet(() -> {
            synchronized (this) {
                final var found = hostnames.get(id);
                return found == null ? new CloudflareResult.NotFound<>() : new CloudflareResult.Ok<>(found, null);
            }
        });
    }

    @Override
    public CloudflareResult<Void> delete(final String id) {
        record(Call.DELETE, id);
        return this.<Void>scripted(Call.DELETE).orElseGet(() -> {
            synchronized (this) {
                hostnames.remove(id);
                return new CloudflareResult.Ok<>(null, null);
            }
        });
    }

    @Override
    public CloudflareResult<List<CloudflareHostname>> list(final int page) {
        record(Call.LIST, String.valueOf(page));
        return this.<List<CloudflareHostname>>scripted(Call.LIST).orElseGet(() -> {
            synchronized (this) {
                final var all = new ArrayList<>(hostnames.values());
                final var from = Math.min((page - 1) * PAGE_SIZE, all.size());
                return new CloudflareResult.Ok<>(List.copyOf(all.subList(from, Math.min(from + PAGE_SIZE, all.size()))), all.size());
            }
        });
    }

    @Override
    public CloudflareResult<Quota> quota() {
        record(Call.QUOTA, "");
        return this.<Quota>scripted(Call.QUOTA).orElseGet(() -> {
            synchronized (this) {
                return new CloudflareResult.Ok<>(new Quota(hostnames.size(), 50_000), null);
            }
        });
    }

    private void record(final Call call, final String argument) {
        final var entry = call.name() + " " + argument;
        calls.add(entry);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            callsInsideATransaction.add(entry);
        }
        final var inUse = connectionsInUse;
        if (inUse != null && inUse.getAsInt() > 0) {
            callsHoldingAConnection.add(entry);
        }
        final var released = blockedReleased;
        if (blocked == call && released != null) {
            blocked = null;
            blockedReleased = null;
            blockedEntered.countDown();
            await(released);
        }
    }

    private synchronized <T> Optional<CloudflareResult<T>> scripted(final Call call) {
        final var queue = scripts.get(call);
        return queue == null || queue.isEmpty() ? Optional.empty() : Optional.of(cast(queue.poll()));
    }

    @SuppressWarnings("unchecked")
    private static <T> CloudflareResult<T> cast(final Object result) {
        return (CloudflareResult<T>) result;
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The blocked Cloudflare call was never released");
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

}
