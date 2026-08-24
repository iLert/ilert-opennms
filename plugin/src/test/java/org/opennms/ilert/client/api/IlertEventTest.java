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

package org.opennms.ilert.client.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

import org.json.JSONException;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies the shape of the JSON the plugin posts to the iLert Events API.
 */
public class IlertEventTest {

    /** 1052 characters, so it exceeds the 1024 character summary limit. */
    private static final String LONG_SUMMARY = "One morning, when Gregor Samsa woke from troubled dreams, he found himself transformed in his bed into a horrible vermin. He lay on his armour-like back, and if he lifted his head a little he could see his brown belly, slightly domed and divided by arches into stiff sections. The bedding was hardly able to cover it and seemed ready to slide off any moment. His many legs, pitifully thin compared with the size of the rest of him, waved about helplessly as he looked. 'What's happened to me?' he thought. It wasn't a dream. His room, a proper human room although a little too small, lay peacefully between its four familiar walls. A collection of textile samples lay spread out on the table - Samsa was a travelling salesman - and above it there hung a picture that he had recently cut out of an illustrated magazine and housed in a nice, gilded frame. It showed a lady fitted out with a fur hat and fur boa who sat upright, raising a heavy fur muff that covered the whole of her lower arm towards the viewer. Gregor then turned to look out the window";

    private static final int SUMMARY_LIMIT = 1024;

    @Test
    public void serialisesAFullyPopulatedEvent() throws IOException, JSONException {
        final IlertEvent event = new IlertEvent();
        event.setEventType(IlertEventType.ALERT);
        event.setSummary("Cat-162223 outage identified on interface 127.0.0.1. Reason:", SUMMARY_LIMIT);
        event.setDetails("**Node:** localhost\n\nCategory array verification");
        event.setAlertKey("uei.opennms.org/nodes/nodeLostService::1:127.0.0.1:Cat-162223");
        event.setPriority(IlertPriority.LOW);
        // The custom details are written flat at the top level of the payload
        event.getCustomDetails().put("nodeLabel", "localhost");
        event.getCustomDetails().put("node_categories", Collections.singletonList("Servers"));
        event.getCustomDetails().put("node_ipAddress", "127.0.0.1");
        event.getLinks().add(new IlertEventLink("https://onms.example.com/opennms/alarm/detail.htm?id=42", "OpenNMS"));

        assertSerialisesTo(event, "full-event-payload.json");
    }

    @Test
    public void truncatesLongSummaryToMaxLength() {
        final IlertEvent event = new IlertEvent();
        event.setSummary(LONG_SUMMARY, SUMMARY_LIMIT);

        // The summary must be truncated before it is sent to iLert.
        assertTrue(event.getSummary().length() <= SUMMARY_LIMIT);
    }

    @Test
    public void doesNotLetCustomDetailsOverwriteReservedFields() {
        final IlertEvent event = new IlertEvent();
        event.setSummary("The real summary");
        // An event parameter can be named anything, including one of the payload's own fields
        event.getCustomDetails().put("summary", "From an event parameter");
        event.getCustomDetails().put("reason", "Kept");

        assertEquals(Collections.singletonMap("reason", "Kept"), event.getFlattenedCustomDetails());
    }

    /**
     * Asserts that the event serialises to the named fixture on the test classpath.
     *
     * <p>Compared leniently: the fixture states the fields that matter, and a field the payload gains
     * later should not break an unrelated assertion. JSONAssert reports the offending path itself, so
     * the documents are not dumped on success.
     */
    private static void assertSerialisesTo(IlertEvent event, String fixture) throws IOException, JSONException {
        final String actual = new ObjectMapper().writeValueAsString(event);
        JSONAssert.assertEquals(readFixture(fixture), actual, JSONCompareMode.LENIENT);
    }

    private static String readFixture(String name) throws IOException {
        try (InputStream in = IlertEventTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("The test fixture '" + name + "' is missing from the test classpath.");
            }
            return new String(in.readAllBytes(), UTF_8);
        }
    }
}