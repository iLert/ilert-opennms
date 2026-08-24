/*
 * Copyright (C) 2026 iLert GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opennms.integrations.ilert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.ilert.client.api.IlertClient;
import org.opennms.ilert.client.api.IlertClientFactory;
import org.opennms.ilert.client.api.IlertEvent;
import org.opennms.ilert.client.api.IlertEventType;
import org.opennms.ilert.client.api.IlertPriority;
import org.opennms.integration.api.v1.config.events.AlarmType;
import org.opennms.integration.api.v1.events.EventForwarder;
import org.opennms.integration.api.v1.model.InMemoryEvent;
import org.opennms.integration.api.v1.model.Severity;
import org.opennms.integration.api.v1.model.immutables.ImmutableAlarm;

/**
 * Verifies the hold-down machinery: which alarms actually reach iLert, and when.
 *
 * <p>The point of the hold-down delay is that an alarm which resolves inside the window never pages
 * anyone, so most of these tests assert that <em>nothing</em> was sent.
 */
public class IlertForwarderHoldDownTest {

    private static final String REDUCTION_KEY = "uei.opennms.org/nodes/nodeLostService::1:127.0.0.1:HTTP";

    /** Long enough that no test races it accidentally, short enough not to slow the suite down. */
    private static final Duration HOLD_DOWN = Duration.ofMillis(400);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    private RecordingClient client;
    private RecordingEventForwarder events;
    private IlertForwarder forwarder;

    @Before
    public void setUp() {
        client = new RecordingClient();
        events = new RecordingEventForwarder();
    }

    @After
    public void tearDown() {
        if (forwarder != null) {
            forwarder.close();
        }
    }

    @Test
    public void sendsTheAlertOnceTheHoldDownDelayHasElapsed() {
        forwarder = newForwarder(HOLD_DOWN, matchEverything());

        forwarder.handleNewOrUpdatedAlarm(problem().build());
        assertEquals("the alert must not be sent before the delay elapses", 0, client.count());

        awaitCount(1);
        assertEquals(IlertEventType.ALERT, client.sent().get(0).getEventType());
        assertEquals(REDUCTION_KEY, client.sent().get(0).getAlertKey());
    }

    @Test
    public void sendsImmediatelyWhenNoHoldDownIsConfigured() {
        forwarder = newForwarder(Duration.ZERO, matchEverything());

        forwarder.handleNewOrUpdatedAlarm(problem().build());

        awaitCount(1);
        assertEquals(IlertEventType.ALERT, client.sent().get(0).getEventType());
    }

    /**
     * The whole point of the feature: a blip that clears inside the window pages nobody, and does not
     * send a resolve either, because iLert never heard about it.
     */
    @Test
    public void anAlarmThatClearsDuringHoldDownIsNeverSent() {
        forwarder = newForwarder(HOLD_DOWN, matchEverything());

        forwarder.handleNewOrUpdatedAlarm(problem().build());
        forwarder.handleNewOrUpdatedAlarm(problem().setSeverity(Severity.CLEARED).build());

        assertNothingSentWithin(HOLD_DOWN.plusMillis(400));
    }

    @Test
    public void anAlarmAcknowledgedDuringHoldDownIsNeverSent() {
        forwarder = newForwarder(HOLD_DOWN, matchEverything());

        forwarder.handleNewOrUpdatedAlarm(problem().build());
        forwarder.handleNewOrUpdatedAlarm(problem().setAcknowledged(true).build());

        assertNothingSentWithin(HOLD_DOWN.plusMillis(400));
    }

    @Test
    public void anAlarmDeletedDuringHoldDownIsNeverSent() {
        forwarder = newForwarder(HOLD_DOWN, matchEverything());

        forwarder.handleNewOrUpdatedAlarm(problem().build());
        forwarder.handleDeletedAlarm(42, REDUCTION_KEY);

        assertNothingSentWithin(HOLD_DOWN.plusMillis(400));
    }

    /**
     * Repeated updates must not postpone the deadline - otherwise a flapping alarm would hold its own
     * alert back indefinitely - but the alert should still carry the newest state, so an escalation
     * during the wait is not lost.
     */
    @Test
    public void repeatedUpdatesKeepTheDeadlineButRefreshThePayload() {
        forwarder = newForwarder(HOLD_DOWN, matchEverything());

        forwarder.handleNewOrUpdatedAlarm(problem().setSeverity(Severity.WARNING).build());
        forwarder.handleNewOrUpdatedAlarm(problem().setSeverity(Severity.WARNING).build());
        forwarder.handleNewOrUpdatedAlarm(problem().setSeverity(Severity.CRITICAL).build());

        awaitCount(1);
        // WARNING would have been LOW; the escalation to CRITICAL has to survive the wait.
        assertEquals(IlertPriority.HIGH, client.sent().get(0).getPriority());
        // One alert per reduction key, not one per update.
        assertSentCountStaysAt(1, HOLD_DOWN.plusMillis(400));
    }

    /**
     * Once the alert is out, an accept or resolve for the same alarm has to be forwarded - there is a
     * live alert in iLert to act on.
     */
    @Test
    public void resolveAfterTheAlertWasSentIsForwarded() {
        forwarder = newForwarder(Duration.ZERO, matchEverything());

        forwarder.handleNewOrUpdatedAlarm(problem().build());
        awaitCount(1);
        forwarder.handleNewOrUpdatedAlarm(problem().setSeverity(Severity.CLEARED).build());
        awaitCount(2);

        assertEquals(IlertEventType.ALERT, client.sent().get(0).getEventType());
        assertEquals(IlertEventType.RESOLVE, client.sent().get(1).getEventType());
    }

    @Test
    public void deletingAnAlarmAfterTheAlertWasSentResolvesIt() {
        forwarder = newForwarder(Duration.ZERO, matchEverything());

        forwarder.handleNewOrUpdatedAlarm(problem().build());
        awaitCount(1);
        forwarder.handleDeletedAlarm(42, REDUCTION_KEY);
        awaitCount(2);

        assertEquals(IlertEventType.RESOLVE, client.sent().get(1).getEventType());
        assertEquals(REDUCTION_KEY, client.sent().get(1).getAlertKey());
    }

    @Test
    public void alarmsTheFilterRejectsAreNotSentAtAll() {
        forwarder = newForwarder(Duration.ZERO, "alarm.reductionKey =~ \".*nothing-matches-this.*\"");

        forwarder.handleNewOrUpdatedAlarm(problem().build());
        // The delete of a filtered alarm must not turn into a resolve for an alert iLert never saw.
        forwarder.handleDeletedAlarm(42, REDUCTION_KEY);

        assertNothingSentWithin(Duration.ofMillis(500));
    }

    /**
     * With no filter configured nothing is forwarded - failing closed, so a half-configured service
     * cannot page a whole team.
     */
    @Test
    public void nothingIsSentWhenNoFilterIsConfigured() {
        forwarder = newForwarder(Duration.ZERO, null);

        forwarder.handleNewOrUpdatedAlarm(problem().build());

        assertNothingSentWithin(Duration.ofMillis(500));
    }

    /**
     * The plugin's own sendEventFailed/sendEventSuccessful alarms must never be forwarded, or a failure
     * to page would page about itself.
     */
    @Test
    public void doesNotForwardItsOwnAlarms() {
        forwarder = newForwarder(Duration.ZERO, matchEverything());

        forwarder.handleNewOrUpdatedAlarm(ImmutableAlarm.newBuilder()
                .setId(43)
                .setReductionKey("uei.opennms.org/ilert/sendEventFailed:core")
                .setSeverity(Severity.MAJOR)
                .setType(AlarmType.PROBLEM)
                .build());

        assertNothingSentWithin(Duration.ofMillis(500));
    }

    /**
     * A snapshot is the authoritative list of live alarms, so ids missing from it are gone for good and
     * must be dropped - otherwise the filtered-alarm set grows without bound.
     */
    @Test
    public void snapshotPrunesFilteredAlarmsThatNoLongerExist() {
        forwarder = newForwarder(Duration.ZERO, "alarm.reductionKey =~ \".*nothing-matches-this.*\"");

        forwarder.handleNewOrUpdatedAlarm(problem().build());
        forwarder.handleAlarmSnapshot(Collections.emptyList());
        // Id 42 is no longer tracked as filtered, so this delete is treated as one for an unknown
        // alarm and forwarded as a resolve.
        forwarder.handleDeletedAlarm(42, REDUCTION_KEY);

        awaitCount(1);
        assertEquals(IlertEventType.RESOLVE, client.sent().get(0).getEventType());
    }

    @Test
    public void closingDropsHeldAlertsInsteadOfSendingThem() {
        forwarder = newForwarder(HOLD_DOWN, matchEverything());

        forwarder.handleNewOrUpdatedAlarm(problem().build());
        forwarder.close();
        forwarder = null;

        assertNothingSentWithin(HOLD_DOWN.plusMillis(400));
    }

    // --- helpers -------------------------------------------------------------------------------

    private IlertForwarder newForwarder(Duration holdDown, String jexlFilter) {
        return new IlertForwarder(events,
                url -> client,
                new IlertPluginConfig("OpenNMS", "http://onms.example.invalid:8980/opennms/alarm/detail.htm?id=%d"),
                new IlertServiceConfig("core", "https://api.ilert.com/api/v1/events/onms/key", jexlFilter, holdDown));
    }

    private static String matchEverything() {
        return "alarm.reductionKey =~ \".*\"";
    }

    private static ImmutableAlarm.Builder problem() {
        return ImmutableAlarm.newBuilder()
                .setId(42)
                .setReductionKey(REDUCTION_KEY)
                .setSeverity(Severity.MAJOR)
                .setType(AlarmType.PROBLEM)
                .setLogMessage("HTTP is down on web01");
    }

    private void awaitCount(int expected) {
        await(() -> client.count() >= expected,
                () -> "expected " + expected + " event(s) to be sent, got " + client.count());
    }

    /**
     * Waits out the window and asserts nothing arrived. Deliberately a real wait: the assertion is
     * about a send that must never happen, which cannot be polled for.
     */
    private void assertNothingSentWithin(Duration window) {
        assertSentCountStaysAt(0, window);
    }

    private void assertSentCountStaysAt(int expected, Duration window) {
        sleep(window);
        assertEquals("unexpected events sent: " + client.sent(), expected, client.count());
    }

    private static void await(BooleanSupplier condition, java.util.function.Supplier<String> message) {
        final long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(10));
        }
        fail(message.get());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }

    /** Records what would have been posted to the iLert Events API. */
    private static final class RecordingClient implements IlertClient {
        private final List<IlertEvent> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<Void> sendEvent(IlertEvent event) {
            sent.add(event);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() throws IOException {
            // nothing to release
        }

        private int count() {
            return sent.size();
        }

        private List<IlertEvent> sent() {
            synchronized (sent) {
                return new ArrayList<>(sent);
            }
        }
    }

    /** Collects the plugin's own success/failure events so they can be asserted on. */
    private static final class RecordingEventForwarder implements EventForwarder {
        private final List<InMemoryEvent> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void sendAsync(InMemoryEvent event) {
            sent.add(event);
        }

        @Override
        public void sendSync(InMemoryEvent event) {
            sent.add(event);
        }
    }

    /** Guards against the fakes silently doing nothing if an interface gains a method. */
    @Test
    public void theFakeClientRecordsWhatItIsGiven() {
        final IlertClientFactory factory = url -> client;
        final IlertEvent event = new IlertEvent();
        event.setEventType(IlertEventType.ALERT);
        factory.getClient("https://example.invalid").sendEvent(event);
        assertTrue(client.sent().contains(event));
    }
}
