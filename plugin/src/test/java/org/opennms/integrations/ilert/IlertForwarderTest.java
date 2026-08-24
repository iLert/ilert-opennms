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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import org.junit.Test;
import org.opennms.ilert.client.api.IlertEvent;
import org.opennms.ilert.client.api.IlertEventType;
import org.opennms.ilert.client.api.IlertPriority;
import org.opennms.integration.api.v1.config.events.AlarmType;
import org.opennms.integration.api.v1.model.Severity;
import org.opennms.integration.api.v1.model.immutables.ImmutableAlarm;
import org.opennms.integration.api.v1.model.immutables.ImmutableDatabaseEvent;
import org.opennms.integration.api.v1.model.immutables.ImmutableEventParameter;
import org.opennms.integration.api.v1.model.immutables.ImmutableIpInterface;
import org.opennms.integration.api.v1.model.immutables.ImmutableNode;

/**
 * Verifies how an OpenNMS {@link org.opennms.integration.api.v1.model.Alarm} is mapped onto the
 * payload sent to the iLert Events API.
 */
public class IlertForwarderTest {

    private static final String REDUCTION_KEY = "uei.opennms.org/nodes/nodeLostService::1:127.0.0.1:HTTP";

    @Test
    public void mapsUnacknowledgedProblemToAlert() {
        IlertEvent event = createEvent(alarm().setSeverity(Severity.MAJOR).setType(AlarmType.PROBLEM));
        assertEquals(IlertEventType.ALERT, event.getEventType());
    }

    @Test
    public void mapsAcknowledgedAlarmToAccept() {
        IlertEvent event = createEvent(alarm().setSeverity(Severity.MAJOR).setAcknowledged(true));
        assertEquals(IlertEventType.ACCEPT, event.getEventType());
    }

    @Test
    public void mapsClearedSeverityToResolve() {
        IlertEvent event = createEvent(alarm().setSeverity(Severity.CLEARED));
        assertEquals(IlertEventType.RESOLVE, event.getEventType());
    }

    @Test
    public void mapsResolutionAlarmTypeToResolve() {
        IlertEvent event = createEvent(alarm().setSeverity(Severity.NORMAL).setType(AlarmType.RESOLUTION));
        assertEquals(IlertEventType.RESOLVE, event.getEventType());
    }

    /**
     * An alarm can be acknowledged and then clear - resolving it must win over accepting it, otherwise
     * the alert would stay open in iLert.
     */
    @Test
    public void resolveTakesPrecedenceOverAccept() {
        IlertEvent event = createEvent(alarm().setSeverity(Severity.CLEARED).setAcknowledged(true));
        assertEquals(IlertEventType.RESOLVE, event.getEventType());
    }

    @Test
    public void mapsSeverityToPriority() {
        assertEquals(IlertPriority.HIGH, createEvent(alarm().setSeverity(Severity.CRITICAL)).getPriority());
        assertEquals(IlertPriority.HIGH, createEvent(alarm().setSeverity(Severity.MAJOR)).getPriority());
        assertEquals(IlertPriority.LOW, createEvent(alarm().setSeverity(Severity.MINOR)).getPriority());
        assertEquals(IlertPriority.LOW, createEvent(alarm().setSeverity(Severity.WARNING)).getPriority());
        assertEquals(IlertPriority.LOW, createEvent(alarm().setSeverity(Severity.NORMAL)).getPriority());
        assertEquals(IlertPriority.LOW, createEvent(alarm().setSeverity(Severity.INDETERMINATE)).getPriority());
        assertEquals(IlertPriority.LOW, createEvent(alarm().setSeverity(Severity.CLEARED)).getPriority());
        // A severity is not guaranteed to be set on the model
        assertEquals(IlertPriority.LOW, createEvent(alarm()).getPriority());
    }

    @Test
    public void prefersLogMessageAsSummary() {
        IlertEvent event = createEvent(alarm()
                .setLogMessage("  HTTP outage identified on interface 127.0.0.1.  ")
                .setDescription("A much longer description"));
        // Also asserts the summary is trimmed
        assertEquals("HTTP outage identified on interface 127.0.0.1.", event.getSummary());
    }

    @Test
    public void fallsBackToDescriptionWhenLogMessageIsBlank() {
        assertEquals("The description", createEvent(alarm().setDescription("The description")).getSummary());
        assertEquals("The description",
                createEvent(alarm().setLogMessage("   ").setDescription("The description")).getSummary());
    }

    /**
     * iLert requires a summary on ALERT events, so the reduction key is the last resort.
     */
    @Test
    public void fallsBackToReductionKeyWhenNothingElseIsSet() {
        assertEquals(REDUCTION_KEY, createEvent(alarm()).getSummary());
    }

    @Test
    public void truncatesLongSummary() {
        String longLogMessage = "x".repeat(2000);
        assertEquals(1024, createEvent(alarm().setLogMessage(longLogMessage)).getSummary().length());
    }

    /**
     * OpenNMS builds the log message from a template, so an event whose substituted parm is empty
     * leaves the label that introduced it dangling at the end of the title.
     */
    @Test
    public void dropsALabelLeftDanglingAtTheEndOfTheSummary() {
        assertEquals("ICMP outage identified on interface 10.0.0.1.",
                createEvent(alarm().setLogMessage("ICMP outage identified on interface 10.0.0.1. Reason:")).getSummary());
        // The colon does not have to be flush against the label
        assertEquals("ICMP outage identified on interface 10.0.0.1.",
                createEvent(alarm().setLogMessage("ICMP outage identified on interface 10.0.0.1. Reason :  ")).getSummary());
        // A template can trail more than one of them
        assertEquals("Service unavailable.",
                createEvent(alarm().setLogMessage("Service unavailable. Node: Reason:")).getSummary());
    }

    /**
     * Only the single word introducing the missing parm is dropped - the prose before it is the
     * message, and the label itself is no loss because every parm is repeated in the details block.
     */
    @Test
    public void dropsOnlyTheLabelWordAndLeavesTheProseBeforeIt() {
        assertEquals("Service ICMP unavailable",
                createEvent(alarm().setLogMessage("Service ICMP unavailable Reason:")).getSummary());
    }

    @Test
    public void keepsAColonThatIsNotTrailingALabel() {
        // Mid-title colons introduce something, so they stay
        assertEquals("Reason: link down",
                createEvent(alarm().setLogMessage("Reason: link down")).getSummary());
        // A trailing colon not preceded by a word is not a label - an address or port keeps it
        assertEquals("Lost contact with 10.0.0.1:",
                createEvent(alarm().setLogMessage("Lost contact with 10.0.0.1:")).getSummary());
    }

    /**
     * A log message that is nothing but a dangling label says nothing, so the summary falls through
     * to the next candidate rather than being sent empty.
     */
    @Test
    public void fallsThroughWhenNothingIsLeftAfterDroppingTheLabel() {
        assertEquals("The description",
                createEvent(alarm().setLogMessage("Reason:").setDescription("The description")).getSummary());
        assertEquals(REDUCTION_KEY, createEvent(alarm().setLogMessage("Reason:")).getSummary());
    }

    /**
     * OpenNMS event descriptions are HTML by convention, but iLert renders the details field as
     * markdown - so the tags used to show up verbatim in the alert.
     */
    @Test
    public void stripsTheHtmlOutOfTheDescription() {
        IlertEvent event = createEvent(alarm()
                .setNode(node().setLabel("localhost").build())
                .setDescription("<p>A HTTP outage was identified on interface 127.0.0.1 because of the"
                        + " following condition: .</p> <p> A new Outage record has been created and service"
                        + " level availability calculations will be impacted until this outage is resolved.</p>"));

        assertEquals("""
                        **Node:** localhost
                        
                        A HTTP outage was identified on interface 127.0.0.1 because of the following condition: .
                        
                        A new Outage record has been created and service level availability calculations will be\
                         impacted until this outage is resolved.""",
                event.getDetails());
    }

    @Test
    public void unescapesEntitiesAndConvertsBreaksAndLists() {
        assertEquals("a & b < c", IlertForwarder.toPlainText("a &amp; b &lt; c"));
        assertEquals("one\ntwo", IlertForwarder.toPlainText("one<br/>two"));
        assertEquals("items:\n- first\n- second", IlertForwarder.toPlainText("items:<ul><li>first</li><li>second</li></ul>"));
        // '&amp;' is unescaped last, so an escaped entity is not unescaped twice
        assertEquals("&lt;", IlertForwarder.toPlainText("&amp;lt;"));
        assertNull(IlertForwarder.toPlainText("<p> </p>"));
        assertNull(IlertForwarder.toPlainText(null));
    }

    /**
     * The title has to be one line, and the description is its second-choice source.
     */
    @Test
    public void summaryFromAnHtmlDescriptionIsPlainAndSingleLine() {
        IlertEvent event = createEvent(alarm().setDescription("<p>First line.</p><p>Second line.</p>"));
        assertEquals("First line. Second line.", event.getSummary());
    }

    @Test
    public void buildsMarkdownDetails() {
        IlertEvent event = createEvent(alarm()
                .setNode(node().setLabel("web01").build())
                .setManagedObjectType("service")
                .setManagedObjectInstance("HTTP")
                .setDescription("The HTTP service poller failed."));

        assertEquals("**Node:** web01\n**Component:** service - HTTP\n\nThe HTTP service poller failed.",
                event.getDetails());
    }

    @Test
    public void omitsDetailsWhenThereIsNothingToDescribe() {
        assertNull(createEvent(alarm().setLogMessage("Something happened")).getDetails());
    }

    /**
     * The component line needs both halves to be meaningful, so neither alone should render it.
     */
    @Test
    public void omitsComponentLineWhenOnlyOneHalfIsKnown() {
        assertNull(createEvent(alarm().setManagedObjectType("service")).getDetails());
        assertNull(createEvent(alarm().setManagedObjectInstance("HTTP")).getDetails());
    }

    @Test
    public void addsNodeAndEventParametersAsCustomDetails() throws UnknownHostException {
        IlertEvent event = createEvent(alarm()
                .setNode(node()
                        .setLabel("web01")
                        .setCategories(Arrays.asList("Servers", "Test"))
                        .addIpInterface(ImmutableIpInterface.newBuilder()
                                .setIpAddress(InetAddress.getByName("127.0.0.1"))
                                .build())
                        .build())
                .setLastEvent(ImmutableDatabaseEvent.newBuilder()
                        .setUei("uei.opennms.org/nodes/nodeLostService")
                        .addParameter(ImmutableEventParameter.newBuilder()
                                .setName("service")
                                .setValue("HTTP")
                                .build())
                        .build()));

        assertEquals("HTTP", event.getCustomDetails().get("service"));
        assertEquals("web01", event.getCustomDetails().get("nodeLabel"));
        assertEquals(Arrays.asList("Servers", "Test"), event.getCustomDetails().get("node_categories"));
        // getHostAddress(), so no leading slash from InetAddress.toString()
        assertEquals("127.0.0.1", event.getCustomDetails().get("node_ipAddress"));
    }

    @Test
    public void omitsNodeCategoriesWhenTheNodeHasNone() {
        IlertEvent event = createEvent(alarm().setNode(node().setLabel("web01").build()));

        assertEquals("web01", event.getCustomDetails().get("nodeLabel"));
        assertFalse(event.getCustomDetails().containsKey("node_categories"));
        assertFalse(event.getCustomDetails().containsKey("node_ipAddress"));
    }

    /**
     * An event parameter can be named anything, including one of the keys the plugin derives from the
     * node. The parameter is set first and must not be overwritten.
     */
    @Test
    public void doesNotLetNodeDetailsOverwriteAnEventParameter() {
        IlertEvent event = createEvent(alarm()
                .setNode(node().setLabel("from-the-node").build())
                .setLastEvent(ImmutableDatabaseEvent.newBuilder()
                        .setUei("uei.opennms.org/nodes/nodeLostService")
                        .addParameter(ImmutableEventParameter.newBuilder()
                                .setName("nodeLabel")
                                .setValue("from-the-parameter")
                                .build())
                        .build()));

        assertEquals("from-the-parameter", event.getCustomDetails().get("nodeLabel"));
    }

    private static IlertEvent createEvent(ImmutableAlarm.Builder alarm) {
        return IlertForwarder.createEvent(alarm.build(), new IlertEvent());
    }

    private static ImmutableAlarm.Builder alarm() {
        return ImmutableAlarm.newBuilder()
                .setId(42)
                .setReductionKey(REDUCTION_KEY);
    }

    private static ImmutableNode.Builder node() {
        return ImmutableNode.newBuilder().setId(1);
    }
}
