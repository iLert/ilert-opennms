/*
 * Copyright (C) 2026 ilert GmbH
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

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.opennms.ilert.client.api.IlertClient;
import org.opennms.ilert.client.api.IlertClientFactory;
import org.opennms.ilert.client.api.IlertEvent;
import org.opennms.ilert.client.api.IlertEventLink;
import org.opennms.ilert.client.api.IlertEventType;
import org.opennms.ilert.client.api.IlertPriority;
import org.opennms.integration.api.v1.alarms.AlarmLifecycleListener;
import org.opennms.integration.api.v1.config.events.AlarmType;
import org.opennms.integration.api.v1.events.EventForwarder;
import org.opennms.integration.api.v1.model.Alarm;
import org.opennms.integration.api.v1.model.DatabaseEvent;
import org.opennms.integration.api.v1.model.EventParameter;
import org.opennms.integration.api.v1.model.Severity;
import org.opennms.integration.api.v1.model.immutables.ImmutableEventParameter;
import org.opennms.integration.api.v1.model.immutables.ImmutableInMemoryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class IlertForwarder implements AlarmLifecycleListener, Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(IlertForwarder.class);

    private static final String ILERT_UEI_PREFIX = "uei.opennms.org/ilert";
    private static final String SEND_EVENT_FAILED_UEI = ILERT_UEI_PREFIX + "/sendEventFailed";
    private static final String SEND_EVENT_SUCCESSFUL_UEI = ILERT_UEI_PREFIX + "/sendEventSuccessful";
    private static final int SUMMARY_MAX_LENGTH = 1024;

    /** How many times an ALERT is attempted before we give up and raise sendEventFailed. */
    private static final int MAX_SEND_ATTEMPTS = 3;
    private static final Duration INITIAL_SEND_RETRY_BACKOFF = Duration.ofSeconds(2);
    private static final int SEND_RETRY_BACKOFF_MULTIPLIER = 4;

    /**
     * Backstop for a single send attempt. The http client has its own (shorter) timeouts, so this
     * only guards against a call that never completes at all.
     */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(60);

    // Used to turn the HTML in OpenNMS event descriptions into plain text. Applied in this order.
    private static final Pattern BR_TAG = Pattern.compile("(?i)<\\s*br\\s*/?\\s*>");
    /** Closing block tags become a blank line, so markdown still renders separate paragraphs. */
    private static final Pattern BLOCK_END_TAG = Pattern.compile("(?i)<\\s*/\\s*(p|div|tr|h[1-6])\\s*>");
    /** Opening list items become the break plus a bullet; the closing tag is left to ANY_TAG. */
    private static final Pattern LIST_ITEM_TAG = Pattern.compile("(?i)<\\s*li[^>]*>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HORIZONTAL_WHITESPACE_RUN = Pattern.compile("[ \\t\\x0B\\f]+");
    private static final Pattern PADDED_NEWLINE = Pattern.compile("[ \\t]*\\R[ \\t]*");
    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\\n{3,}");

    /** Collapses everything onto one line, for the alert title. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private final EventForwarder eventForwarder;
    private final IlertClient ilertClient;
    private final IlertPluginConfig pluginConfig;
    private final IlertServiceConfig serviceConfig;
    private final JexlExpression jexlFilterExpression;
    private final ScheduledExecutorService scheduler;

    /**
     * ALERTs waiting out the configured hold-down delay, keyed by reduction key. An alarm that
     * accepts or resolves before its entry fires never reaches ilert at all.
     */
    private final Map<String, HeldAlert> heldAlerts = new ConcurrentHashMap<>();

    /**
     * Used to track alarms that were filtered and not forwarded to ilert.
     */
    private final Set<Integer> alarmIdsFiltered = new ConcurrentSkipListSet<>();

    public IlertForwarder(EventForwarder eventForwarder, IlertClientFactory ilertClientFactory, IlertPluginConfig pluginConfig, IlertServiceConfig serviceConfig) {
        this.eventForwarder = Objects.requireNonNull(eventForwarder);
        this.pluginConfig = Objects.requireNonNull(pluginConfig);
        this.serviceConfig = Objects.requireNonNull(serviceConfig);
        ilertClient = ilertClientFactory.getClient(serviceConfig.url());
        // One thread per service: it both waits out hold-down delays and performs the retrying
        // sends, so a service that is slow to reach ilert delays only its own alerts.
        scheduler = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder().setNameFormat("ilert-Forwarder-" + serviceConfig.pid() + "-%d").build());

        jexlFilterExpression = compileFilter(serviceConfig);
    }

    /**
     * Compiles the configured JEXL filter, or returns {@code null} if there is none or if it does not
     * compile. A {@code null} expression means no alarms are forwarded for this service.
     */
    private static JexlExpression compileFilter(IlertServiceConfig serviceConfig) {
        final String filter = serviceConfig.jexlFilter();
        if (Strings.isNullOrEmpty(filter)) {
            LOG.warn("No '{}' configured for ilert service pid: {}. No alarms will be forwarded until one is set.",
                    IlertServiceManager.JEXL_FILTER_PROP, serviceConfig.pid());
            return null;
        }
        // JEXL 3.3 defaults to RESTRICTED introspection permissions, which refuse to invoke
        // getters on non-JDK classes such as the OpenNMS Alarm model. That makes filters like
        // "alarm.reductionKey =~ ..." throw "undefined property" and silently drops every alarm.
        // These filters are operator-authored trusted config, so opt back into unrestricted access.
        JexlEngine jexl = new JexlBuilder().permissions(JexlPermissions.UNRESTRICTED).create();
        try {
            return jexl.createExpression(filter);
        } catch (JexlException e) {
            // Let the service start anyway (forwarding nothing) so the operator can correct the
            // filter with config:update instead of chasing a stack trace out of the config admin.
            LOG.error("The configured '{}' for ilert service pid: {} could not be parsed: '{}'." +
                            " No alarms will be forwarded until it is corrected.",
                    IlertServiceManager.JEXL_FILTER_PROP, serviceConfig.pid(), filter, e);
            return null;
        }
    }

    /**
     * Whether this alarm is one the plugin itself raised (sendEventFailed / sendEventSuccessful).
     * These are never forwarded, and are also never tracked in {@link #alarmIdsFiltered} - on a busy
     * system they are the single largest source of entries in it.
     */
    private static boolean isPluginGeneratedAlarm(String reductionKey) {
        return reductionKey != null && reductionKey.startsWith(ILERT_UEI_PREFIX);
    }

    private boolean shouldProcess(Alarm alarm) {
        if (jexlFilterExpression == null) {
            LOG.debug("No JEXL expression found, not evaluating alarm.");
            return false;
        }
        try {
            return testAlarmAgainstExpression(jexlFilterExpression, alarm);
        } catch (RuntimeException e) {
            // A filter that throws (or returns a non-boolean) must not escape into the alarm
            // lifecycle callback, but it must not be silent either - that looks exactly like an
            // alarm being filtered out on purpose.
            LOG.warn("Evaluating the configured '{}' ('{}') against the alarm with reduction-key: {} failed." +
                            " The alarm will not be forwarded.",
                    IlertServiceManager.JEXL_FILTER_PROP, serviceConfig.jexlFilter(), alarm.getReductionKey(), e);
            return false;
        }
    }

    @Override
    public void handleNewOrUpdatedAlarm(Alarm alarm) {
        if (isPluginGeneratedAlarm(alarm.getReductionKey())) {
            // Forwarding these would page about our own failure to page, and a forwarded
            // sendEventSuccessful alarm would generate another success event: a feedback loop.
            return;
        }
        if (!shouldProcess(alarm)) {
            // Remember the alarms that were filtered & not processed, so that we can skip
            // the deletes as well when we get callbacks for these
            alarmIdsFiltered.add(alarm.getId());
            return;
        }
        // We may have previously filtered the alarm, but decided to process it now
        alarmIdsFiltered.remove(alarm.getId());

        IlertEvent ilertEvent = toEvent(alarm);

        String reductionKey = alarm.getReductionKey();
        switch (ilertEvent.getEventType()) {
            case ALERT:
                holdDown(reductionKey, ilertEvent);
                break;
            case ACCEPT:
                acceptEvent(ilertEvent, reductionKey);
                break;
            case RESOLVE:
                resolveEvent(ilertEvent, reductionKey);
                break;
        }
    }

    /**
     * Schedules an ALERT to be sent once the configured hold-down delay has elapsed, so an alarm that
     * clears on its own within that window never pages anyone.
     *
     * <p>While an ALERT for this reduction key is already waiting, its deadline stands and only its
     * payload is replaced. That way an alarm which escalates mid-wait pages with its latest severity,
     * and an alarm that updates continuously cannot keep pushing its own deadline out forever.
     */
    private void holdDown(String reductionKey, IlertEvent ilertEvent) {
        final HeldAlert waiting = heldAlerts.get(reductionKey);
        if (waiting != null) {
            waiting.replaceEvent(ilertEvent);
            LOG.debug("An alert for reduction-key: {} is already held down; refreshed its payload.", reductionKey);
            return;
        }

        final Duration holdDownDelay = serviceConfig.holdDownDelay();
        LOG.debug("Holding down the alert for reduction-key: {} for {}", reductionKey, holdDownDelay);
        final HeldAlert held = new HeldAlert(ilertEvent);
        heldAlerts.put(reductionKey, held);
        // Scheduled only after the map entry exists, so that a zero delay cannot fire before the
        // entry it has to claim is visible. Claiming it is also what makes cancellation authoritative:
        // whoever removes the entry first decides whether this alert is sent.
        held.setTimer(scheduler.schedule(() -> {
            if (heldAlerts.remove(reductionKey, held)) {
                sendWithRetries(reductionKey, held.event());
            }
        }, holdDownDelay.toMillis(), TimeUnit.MILLISECONDS));
    }

    private void resolveEvent(IlertEvent ilertEvent, String reductionKey) {
        LOG.debug("Resolving alarm with reduction-key: {}", reductionKey);
        if (cancelHeldAlert(reductionKey)) {
            return;
        }
        sendIlertEvent(reductionKey, ilertEvent);
    }

    private void acceptEvent(IlertEvent ilertEvent, String reductionKey) {
        LOG.debug("Accepting alarm with reduction-key: {}", reductionKey);
        if (cancelHeldAlert(reductionKey)) {
            return;
        }
        sendIlertEvent(reductionKey, ilertEvent);
    }

    /**
     * Drops a held-down ALERT for this reduction key, if one is still waiting. Returns whether there
     * was one - if so, ilert never heard about this alarm, so there is nothing to accept or resolve.
     */
    private boolean cancelHeldAlert(String reductionKey) {
        final HeldAlert held = heldAlerts.remove(reductionKey);
        if (held == null) {
            return false;
        }
        // Removing the entry above already prevents the send; cancelling only releases the timer.
        held.cancelTimer();
        LOG.debug("Dropped the held-down alert for reduction-key: {} before it was sent.", reductionKey);
        return true;
    }

    /**
     * Sends an event without retrying, reporting the outcome once it completes. Used for the event
     * types that are sent directly from an alarm lifecycle callback, where we must not block.
     */
    private void sendIlertEvent(String reductionKey, IlertEvent ilertEvent) {
        LOG.info("Sending event for alarm with reduction-key: {}", reductionKey);
        ilertClient.sendEvent(ilertEvent).whenComplete((v, ex) -> {
            if (ex != null) {
                onSendFailed(reductionKey, ex);
            } else {
                onSendSucceeded(reductionKey);
            }
        });
    }

    /**
     * Sends an event from the task consumer thread, retrying a handful of times before giving up.
     *
     * ALERTs are the events worth retrying: dropping one means a page that never reaches anybody,
     * and a single transient failure (a timeout, a proxy error, an ilert-side 5xx) would otherwise
     * be enough to lose it. Because this runs on the service's own consumer thread, waiting here
     * delays that service's other held-down alerts but never blocks OpenNMS itself.
     */
    private void sendWithRetries(String reductionKey, IlertEvent ilertEvent) {
        Duration backoff = INITIAL_SEND_RETRY_BACKOFF;
        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            LOG.info("Sending event for alarm with reduction-key: {} (attempt {} of {})", reductionKey, attempt, MAX_SEND_ATTEMPTS);
            try {
                ilertClient.sendEvent(ilertEvent).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                onSendSucceeded(reductionKey);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("Interrupted while sending event for alarm with reduction-key: {}. Giving up.", reductionKey);
                return;
            } catch (ExecutionException | TimeoutException e) {
                // A timeout does not cancel the in-flight call, so a retry may duplicate it. That is
                // safe here: ilert deduplicates by alertKey, so a repeated ALERT is not a second alert.
                final Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                if (attempt >= MAX_SEND_ATTEMPTS) {
                    onSendFailed(reductionKey, cause);
                    return;
                }
                LOG.warn("Sending event for alarm with reduction-key: {} failed on attempt {} of {}. Retrying in {}.",
                        reductionKey, attempt, MAX_SEND_ATTEMPTS, backoff, cause);
                try {
                    Thread.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOG.info("Interrupted while waiting to retry the event for alarm with reduction-key: {}. Giving up.", reductionKey);
                    return;
                }
                backoff = backoff.multipliedBy(SEND_RETRY_BACKOFF_MULTIPLIER);
            }
        }
    }

    private void onSendSucceeded(String reductionKey) {
        LOG.info("The event for the alarm with reduction-key: {} reached ilert.", reductionKey);
        raisePluginEvent(SEND_EVENT_SUCCESSFUL_UEI, reductionKey, null);
    }

    private void onSendFailed(String reductionKey, Throwable cause) {
        LOG.warn("The event for the alarm with reduction-key: {} could not be sent to ilert.", reductionKey, cause);
        raisePluginEvent(SEND_EVENT_FAILED_UEI, reductionKey, Strings.nullToEmpty(cause.getMessage()));
    }

    /**
     * Raises one of the plugin's own events, which is how the plugin reports on itself: if ilert cannot
     * be reached then nobody is being paged, and nothing else would say so.
     *
     * <p>The event definitions address these parameters by name, so the order they are added in does
     * not matter.
     *
     * @param message the failure detail, or {@code null} to leave the parameter off entirely
     */
    private void raisePluginEvent(String uei, String reductionKey, String message) {
        final ImmutableInMemoryEvent.Builder event = ImmutableInMemoryEvent.newBuilder()
                .setUei(uei)
                .setSource(IlertForwarder.class.getName())
                .addParameter(parameter("reductionKey", reductionKey))
                .addParameter(parameter("pid", serviceConfig.pid()));
        if (message != null) {
            event.addParameter(parameter("message", message));
        }
        eventForwarder.sendAsync(event.build());
    }

    private static ImmutableEventParameter parameter(String name, String value) {
        return ImmutableEventParameter.newBuilder()
                .setName(name)
                .setValue(value)
                .build();
    }

    @Override
    public void handleDeletedAlarm(int alarmId, String reductionKey) {
        if (isPluginGeneratedAlarm(reductionKey)) {
            // We never sent an alert for it, so there is nothing to resolve. This check is what lets
            // handleNewOrUpdatedAlarm skip tracking these ids without the delete turning into a
            // spurious RESOLVE for an alertKey ilert has never seen.
            return;
        }
        if (alarmIdsFiltered.remove(alarmId)) {
            // This alarm was filtered out and not forwarded to ilert, do not forward the delete
            return;
        }
        if (cancelHeldAlert(reductionKey)) {
            return;
        }

        final IlertEvent e = new IlertEvent();
        e.setEventType(IlertEventType.RESOLVE);
        e.setAlertKey(reductionKey);
        LOG.info("Sending resolve for deleted alarm with reduction-key: {}", reductionKey);
        // Goes through the shared send path so that a failed resolve raises sendEventFailed like any
        // other failed send - otherwise the alert silently stays open in ilert.
        sendIlertEvent(reductionKey, e);
    }

    @Override
    public void handleAlarmSnapshot(List<Alarm> alarms) {
        // The snapshot is documented as the authoritative, complete list of alarms, so anything we are
        // still tracking that is absent from it no longer exists and we will never get a delete for
        // it. Without this the set only ever grows - across restarts and missed deletes it is a leak.
        final Set<Integer> currentAlarmIds = alarms.stream()
                .map(Alarm::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (alarmIdsFiltered.retainAll(currentAlarmIds)) {
            LOG.debug("Pruned filtered-alarm ids that are no longer present. Tracking {} ids.", alarmIdsFiltered.size());
        }
        // TODO: Add full snapshot handling support (forwarding state resync) - that needs some
        // persistent state handling before we do it.
    }

    public IlertEvent toEvent(Alarm alarm) {
        final IlertEvent e = new IlertEvent();
        // The integration key is carried in the configured events url (in its path),
        // so it is not sent in the payload.
        e.setAlertKey(alarm.getReductionKey());

        // Attach a deep link back to the alarm detail page in OpenNMS. A misconfigured url pattern
        // costs us the link, not the alert.
        final String alarmDetailsUrl = pluginConfig.buildAlarmDetailsUrl(alarm.getId());
        if (alarmDetailsUrl != null) {
            final String label = Strings.isNullOrEmpty(pluginConfig.getClient()) ? "OpenNMS" : pluginConfig.getClient();
            e.getLinks().add(new IlertEventLink(alarmDetailsUrl, label));
        }

        return createEvent(alarm, e);
    }

    public static IlertEvent createEvent(Alarm alarm, IlertEvent ilertEvent) {
        if (Severity.CLEARED.equals(alarm.getSeverity()) || AlarmType.RESOLUTION.equals(alarm.getType())) {
            ilertEvent.setEventType(IlertEventType.RESOLVE);
        } else if (alarm.isAcknowledged()) {
            ilertEvent.setEventType(IlertEventType.ACCEPT);
        } else {
            ilertEvent.setEventType(IlertEventType.ALERT);
        }

        // Summary (ilert renders this as the alert title, and requires it for ALERT events).
        // Prefer the log message, then fall back to the description, then the reduction key, so the
        // summary is never empty. Truncated to SUMMARY_MAX_LENGTH characters.
        String summary = toPlainText(alarm.getLogMessage());
        if (Strings.isNullOrEmpty(summary)) {
            summary = toPlainText(alarm.getDescription());
        }
        if (Strings.isNullOrEmpty(summary)) {
            summary = alarm.getReductionKey();
        }
        if (summary != null) {
            // A title has to be a single line
            summary = WHITESPACE_RUN.matcher(summary).replaceAll(" ").trim();
            if (summary.length() > SUMMARY_MAX_LENGTH) {
                LOG.info("Alarm with key '{}' has a summary longer than '{}' characters, truncating.", alarm.getReductionKey(), SUMMARY_MAX_LENGTH);
            }
            ilertEvent.setSummary(summary, SUMMARY_MAX_LENGTH);
        }

        // Severity -> Priority (HIGH escalates, LOW does not)
        ilertEvent.setPriority(IlertPriority.fromOnmsSeverity(alarm.getSeverity()));

        // Build a markdown details block (ilert renders markdown in the details field)
        ilertEvent.setDetails(buildDetails(alarm));

        // Add all of the event parameters as custom details
        final DatabaseEvent dbEvent = alarm.getLastEvent();
        if (dbEvent != null) {
            ilertEvent.getCustomDetails().putAll(toCustomDetails(dbEvent.getParameters()));
        }
        if (alarm.getNode() != null) {
            // Add the node label to details
            if (alarm.getNode().getLabel() != null && !ilertEvent.getCustomDetails().containsKey("nodeLabel")) {
                ilertEvent.getCustomDetails().put("nodeLabel", alarm.getNode().getLabel());
            }
            // Add categories as a JSON array; omit the key entirely when the node has none
            if (alarm.getNode().getCategories() != null && !alarm.getNode().getCategories().isEmpty()
                    && !ilertEvent.getCustomDetails().containsKey("node_categories")) {
                ilertEvent.getCustomDetails().put("node_categories", alarm.getNode().getCategories());
            }
            // Add the first IP address (getHostAddress avoids the leading slash from InetAddress.toString())
            if (!alarm.getNode().getIpInterfaces().isEmpty()
                    && alarm.getNode().getIpInterfaces().get(0).getIpAddress() != null
                    && !ilertEvent.getCustomDetails().containsKey("node_ipAddress")) {
                ilertEvent.getCustomDetails().put("node_ipAddress", alarm.getNode().getIpInterfaces().get(0).getIpAddress().getHostAddress());
            }
        }
        return ilertEvent;
    }

    /**
     * Assembles a markdown description of the alarm for the ilert {@code details} field.
     */
    private static String buildDetails(Alarm alarm) {
        final StringBuilder sb = new StringBuilder();
        if (alarm.getNode() != null && alarm.getNode().getLabel() != null) {
            sb.append("**Node:** ").append(alarm.getNode().getLabel()).append('\n');
        }
        if (!Strings.isNullOrEmpty(alarm.getManagedObjectType()) && !Strings.isNullOrEmpty(alarm.getManagedObjectInstance())) {
            sb.append("**Component:** ")
              .append(String.format("%s - %s", alarm.getManagedObjectType(), alarm.getManagedObjectInstance()))
              .append('\n');
        }
        final String description = toPlainText(alarm.getDescription());
        if (!Strings.isNullOrEmpty(description)) {
            sb.append('\n').append(description);
        }
        String details = sb.toString().trim();
        return details.isEmpty() ? null : details;
    }

    // Converts the HTML that OpenNMS event definitions conventionally use into plain text.

    static String toPlainText(String html) {
        if (Strings.isNullOrEmpty(html)) {
            return null;
        }
        String text = html;
        // Turn the block-level markup into line breaks before dropping the rest of the tags
        text = BR_TAG.matcher(text).replaceAll("\n");
        text = BLOCK_END_TAG.matcher(text).replaceAll("\n\n");
        text = LIST_ITEM_TAG.matcher(text).replaceAll("\n- ");
        text = ANY_TAG.matcher(text).replaceAll("");
        // Unescape the handful of entities that actually turn up in event descriptions. '&amp;' is
        // done last so that an escaped entity such as '&amp;lt;' is not unescaped twice.
        text = text.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
        // Tidy up the whitespace the tags left behind
        text = HORIZONTAL_WHITESPACE_RUN.matcher(text).replaceAll(" ");
        text = PADDED_NEWLINE.matcher(text).replaceAll("\n");
        text = BLANK_LINE_RUN.matcher(text).replaceAll("\n\n");
        text = text.trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * The alarm's event parameters, in the order OpenNMS reports them, ready to be merged into the
     * ilert custom details. A parameter with no value is kept rather than dropped: that it was present
     * at all is itself worth seeing on the alert.
     */
    private static Map<String, Object> toCustomDetails(List<EventParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        final Map<String, Object> details = new LinkedHashMap<>();
        for (EventParameter parameter : parameters) {
            details.put(parameter.getName(), parameter.getValue());
        }
        return details;
    }

    @Override
    public void close() {
        // Stop the timers first: a held-down alert that fires during shutdown would try to send on a
        // client that is already closed.
        scheduler.shutdownNow();
        heldAlerts.clear();
        try {
            ilertClient.close();
        } catch (IOException e) {
            LOG.warn("Error while closing ilert client. Resources may not be cleaned up properly.", e);
        }
    }

    public static boolean testAlarmAgainstExpression(JexlExpression expression, Alarm alarm) {
        final JexlContext jc = new MapContext();
        jc.set("alarm", alarm);
        return (boolean)expression.evaluate(jc);
    }

    /**
     * An ALERT that is waiting out its hold-down delay.
     *
     * <p>Both fields are written from the alarm lifecycle callbacks and read on the scheduler thread,
     * so both are volatile. The timer is set immediately after construction rather than in it, because
     * scheduling the timer needs the instance it will be stored against.
     */
    private static final class HeldAlert {
        private volatile IlertEvent event;
        private volatile ScheduledFuture<?> timer;

        private HeldAlert(IlertEvent event) {
            this.event = event;
        }

        private IlertEvent event() {
            return event;
        }

        private void replaceEvent(IlertEvent event) {
            this.event = event;
        }

        private void setTimer(ScheduledFuture<?> timer) {
            this.timer = timer;
        }

        private void cancelTimer() {
            final ScheduledFuture<?> t = timer;
            if (t != null) {
                // Never interrupt: the timer task shares its thread with in-flight sends.
                t.cancel(false);
            }
        }
    }

}
