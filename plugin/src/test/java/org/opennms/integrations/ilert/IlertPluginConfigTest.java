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
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The alarm details url pattern is operator-supplied and is fed to {@link String#format}, so a stray
 * percent sign in it used to throw once per alarm from inside the alarm lifecycle callback - which
 * stopped all forwarding. It must cost us the link, never the alert.
 */
public class IlertPluginConfigTest {

    @Test
    public void buildsTheAlarmDetailsUrl() {
        IlertPluginConfig config = config("http://onms.example.com:8980/opennms/alarm/detail.htm?id=%d");
        assertEquals("http://onms.example.com:8980/opennms/alarm/detail.htm?id=42", config.buildAlarmDetailsUrl(42));
    }

    @Test
    public void acceptsAnEscapedPercentSign() {
        IlertPluginConfig config = config("http://onms.example.com:8980/opennms/a%%20b/detail.htm?id=%d");
        assertEquals("http://onms.example.com:8980/opennms/a%20b/detail.htm?id=42", config.buildAlarmDetailsUrl(42));
    }

    @Test
    public void omitsTheUrlWhenThePatternHasAnUnescapedPercentSign() {
        // e.g. a url-encoded path pasted in without escaping the '%'
        assertNull(config("http://onms.example.com:8980/opennms/a%20b/detail.htm?id=%d").buildAlarmDetailsUrl(42));
    }

    @Test
    public void omitsTheUrlWhenThePatternHasAnUnknownConversion() {
        assertNull(config("http://onms.example.com:8980/opennms/alarm/detail.htm?id=%q").buildAlarmDetailsUrl(42));
    }

    @Test
    public void omitsTheUrlWhenThereIsNoAlarmId() {
        assertNull(config("http://onms.example.com:8980/opennms/alarm/detail.htm?id=%d").buildAlarmDetailsUrl(null));
    }

    private static IlertPluginConfig config(String alarmDetailsUrlPattern) {
        return new IlertPluginConfig("OpenNMS", alarmDetailsUrlPattern);
    }
}
