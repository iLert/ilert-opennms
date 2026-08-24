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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.Test;
import org.opennms.integration.api.v1.config.events.AlarmType;
import org.opennms.integration.api.v1.config.events.EventDefinition;
import org.opennms.integration.api.v1.model.Severity;

/**
 * Checks that the bundled event definitions actually parse and say what the plugin relies on them
 * saying. A mistake in that XML would otherwise only surface once OpenNMS loaded the bundle.
 */
public class PluginEventConfExtensionTest {

    private static final String SUCCESS_UEI = "uei.opennms.org/ilert/sendEventSuccessful";
    private static final String FAILURE_UEI = "uei.opennms.org/ilert/sendEventFailed";

    private final Map<String, EventDefinition> definitions = load();

    private static Map<String, EventDefinition> load() {
        final List<EventDefinition> loaded = new PluginEventConfExtension().getEventDefinitions();
        return loaded.stream().collect(Collectors.toMap(EventDefinition::getUei, Function.identity()));
    }

    @Test
    public void definesBothOfThePluginsOwnEvents() {
        assertEquals(definitions.keySet().toString(), 2, definitions.size());
        assertNotNull(definitions.get(SUCCESS_UEI));
        assertNotNull(definitions.get(FAILURE_UEI));
    }

    /**
     * The forwarder recognises its own alarms by this prefix and refuses to forward them. If a uei ever
     * fell outside it, the plugin would page about its own failure to page.
     */
    @Test
    public void everyUeiSitsUnderThePrefixTheForwarderFiltersOn() {
        for (String uei : definitions.keySet()) {
            assertTrue(uei + " must start with uei.opennms.org/ilert", uei.startsWith("uei.opennms.org/ilert"));
        }
    }

    /**
     * A successful delivery has to carry the failure's reduction key as its clear-key, otherwise a
     * failure alarm stays open after the service recovers.
     *
     * <p>The failure states its own key with the {@code %uei%} placeholder, which OpenNMS expands to
     * that event's uei; the success has to spell the same key out literally. Comparing the two means
     * resolving the placeholder first.
     */
    @Test
    public void aSuccessfulDeliveryClearsTheMatchingFailure() {
        final EventDefinition success = definitions.get(SUCCESS_UEI);
        final EventDefinition failure = definitions.get(FAILURE_UEI);

        assertEquals(AlarmType.RESOLUTION, success.getAlarmData().getType());
        assertEquals(AlarmType.PROBLEM, failure.getAlarmData().getType());
        assertEquals(failure.getAlarmData().getReductionKey().replace("%uei%", FAILURE_UEI),
                success.getAlarmData().getClearKey());
    }

    @Test
    public void aFailureIsCriticalAndSaysWhyItFailed() {
        final EventDefinition failure = definitions.get(FAILURE_UEI);

        assertEquals(Severity.CRITICAL, failure.getSeverity());
        // The message parameter is the only place the underlying cause is reported.
        assertTrue(failure.getDescription(), failure.getDescription().contains("%parm[message]%"));
    }

    /**
     * Both alarms are keyed per service and per alarm, so two services forwarding the same alarm do not
     * collapse into one alarm about the plugin.
     */
    @Test
    public void reductionKeysDistinguishServiceAndAlarm() {
        for (EventDefinition definition : definitions.values()) {
            final String reductionKey = definition.getAlarmData().getReductionKey();
            assertTrue(reductionKey, reductionKey.contains("%parm[pid]%"));
            assertTrue(reductionKey, reductionKey.contains("%parm[reductionKey]%"));
        }
    }
}