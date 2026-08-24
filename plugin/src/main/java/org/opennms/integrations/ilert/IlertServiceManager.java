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

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.opennms.ilert.client.api.IlertClientFactory;
import org.opennms.integration.api.v1.alarms.AlarmLifecycleListener;
import org.opennms.integration.api.v1.events.EventForwarder;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ManagedServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one {@link IlertForwarder} per configured ilert service, creating, replacing and closing them
 * as Config Admin reports changes to the
 * {@code ${OPENNMS_HOME}/etc/org.opennms.plugins.ilert.services-*.cfg} files.
 *
 * <p>Each service is independent: its own events url, its own filter, its own hold-down delay and its
 * own forwarder thread.
 */
public class IlertServiceManager implements ManagedServiceFactory {
    private static final Logger LOG = LoggerFactory.getLogger(IlertServiceManager.class);

    public static final String URL_PROP = "url";
    public static final String JEXL_FILTER_PROP = "jexlFilter";
    public static final String HOLD_DOWN_DELAY_PROP = "holdDownDelay";

    private final BundleContext bundleContext;
    private final EventForwarder eventForwarder;
    private final IlertClientFactory ilertClientFactory;
    private final IlertPluginConfig pluginConfig;

    /**
     * The live services, keyed by configuration pid. Guarded by this instance's monitor rather than
     * being a concurrent map: {@link #updated} reads and then writes it, and that pair has to be
     * atomic as a whole.
     */
    private final Map<String, ManagedService> servicesByPid = new LinkedHashMap<>();

    public IlertServiceManager(BundleContext bundleContext, EventForwarder eventForwarder,
                               IlertClientFactory ilertClientFactory, IlertPluginConfig pluginConfig) {
        this.bundleContext = Objects.requireNonNull(bundleContext);
        this.eventForwarder = Objects.requireNonNull(eventForwarder);
        this.ilertClientFactory = Objects.requireNonNull(ilertClientFactory);
        this.pluginConfig = Objects.requireNonNull(pluginConfig);
    }

    @Override
    public String getName() {
        return "ilert Service Manager";
    }

    @Override
    public synchronized void updated(String pid, Dictionary<String, ?> properties) {
        // A change to an existing service is a replacement: the forwarder holds its configuration for
        // its whole life, so the old one is torn down rather than mutated.
        if (servicesByPid.containsKey(pid)) {
            LOG.info("Reconfiguring the ilert service for pid: {}", pid);
            deleted(pid);
        } else {
            LOG.info("Starting a new ilert service for pid: {}", pid);
        }

        final IlertServiceConfig config = readConfig(pid, properties);
        if (config == null) {
            return;
        }

        final IlertForwarder forwarder = new IlertForwarder(eventForwarder, ilertClientFactory, pluginConfig, config);
        final ServiceRegistration<AlarmLifecycleListener> registration =
                bundleContext.registerService(AlarmLifecycleListener.class, forwarder, null);
        servicesByPid.put(pid, new ManagedService(forwarder, registration));
        LOG.info("The ilert service for pid: {} is running.", pid);
    }

    /**
     * Turns one config admin dictionary into a service configuration.
     *
     * @return the configuration, or {@code null} if it is unusable, in which case this service is left
     *         unstarted rather than started in a state that cannot forward anything.
     */
    private static IlertServiceConfig readConfig(String pid, Dictionary<String, ?> properties) {
        final Map<String, String> props = new LinkedHashMap<>();
        for (String key : Collections.list(properties.keys())) {
            final Object value = properties.get(key);
            props.put(key, value == null ? null : String.valueOf(value));
        }

        final String url = props.get(URL_PROP);
        if (url == null || url.isBlank()) {
            LOG.error("No '{}' is configured for ilert service pid: {}. Set it to the events url of your ilert alert"
                    + " source. This service will not be started.", URL_PROP, pid);
            return null;
        }

        return new IlertServiceConfig(pid, url, props.get(JEXL_FILTER_PROP),
                readHoldDownDelay(pid, props.get(HOLD_DOWN_DELAY_PROP)));
    }

    /**
     * @return the parsed delay, or {@code null} for none. An unparseable value is reported and treated
     *         as none: an optional property should not stop a service from forwarding at all.
     */
    private static Duration readHoldDownDelay(String pid, String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(configured);
        } catch (DateTimeParseException e) {
            LOG.error("The '{}' value '{}' configured for ilert service pid: {} is not an ISO-8601 duration (for"
                            + " example PT5M). Continuing with no hold-down delay.",
                    HOLD_DOWN_DELAY_PROP, configured, pid, e);
            return null;
        }
    }

    /**
     * Invoked by blueprint when this bundle is stopped, updated or uninstalled.
     *
     * <p>OSGi unregisters the alarm lifecycle listeners on its own, but each forwarder also owns a
     * thread and an http client that only {@link #deleted(String)} releases. Without this, every bundle
     * stop would leave one forwarder thread per configured service running for the life of the JVM,
     * still holding its old configuration.
     */
    public synchronized void destroy() {
        if (servicesByPid.isEmpty()) {
            return;
        }
        LOG.info("The bundle is stopping; closing {} ilert service(s).", servicesByPid.size());
        // deleted() removes from the map, so walk a snapshot of the keys instead of the map itself.
        for (String pid : new ArrayList<>(servicesByPid.keySet())) {
            deleted(pid);
        }
    }

    @Override
    public synchronized void deleted(String pid) {
        final ManagedService service = servicesByPid.remove(pid);
        if (service == null) {
            return;
        }
        LOG.info("Stopping the ilert service for pid: {}", pid);
        service.close(pid);
    }

    /**
     * A running forwarder together with the service registration that feeds alarms to it. Both have to
     * be undone when the service goes away, and neither failing may prevent the other.
     */
    private static final class ManagedService {
        private final IlertForwarder forwarder;
        private final ServiceRegistration<AlarmLifecycleListener> registration;

        private ManagedService(IlertForwarder forwarder, ServiceRegistration<AlarmLifecycleListener> registration) {
            this.forwarder = forwarder;
            this.registration = registration;
        }

        private void close(String pid) {
            if (registration != null) {
                try {
                    registration.unregister();
                } catch (RuntimeException e) {
                    LOG.warn("Could not unregister the alarm lifecycle listener for pid: {}.", pid, e);
                }
            }
            if (forwarder != null) {
                // Releases the forwarder's scheduler thread and http client. A forwarder that is not
                // closed keeps its thread alive and can still send held-down alerts using the
                // configuration it was replaced for.
                try {
                    forwarder.close();
                } catch (RuntimeException e) {
                    LOG.warn("Could not close the ilert forwarder for pid: {}. Resources may have leaked.", pid, e);
                }
            }
        }
    }
}