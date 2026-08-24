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

import java.util.IllegalFormatException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IlertPluginConfig {
    private static final Logger LOG = LoggerFactory.getLogger(IlertPluginConfig.class);

    private final String client;
    private final String alarmDetailsUrlPattern;

    /**
     * Whether {@link #alarmDetailsUrlPattern} is a usable format string. Checked once here rather
     * than per alarm, because it is operator-supplied and a bad pattern would otherwise throw on
     * every single alarm from inside the alarm lifecycle callback.
     */
    private final boolean alarmDetailsUrlPatternUsable;

    public IlertPluginConfig(String client, String alarmDetailsUrlPattern) {
        this.client = Objects.requireNonNull(client);
        this.alarmDetailsUrlPattern = Objects.requireNonNull(alarmDetailsUrlPattern);
        this.alarmDetailsUrlPatternUsable = isUsableUrlPattern(alarmDetailsUrlPattern);
    }

    private static boolean isUsableUrlPattern(String pattern) {
        try {
            String.format(pattern, 1);
            return true;
        } catch (IllegalFormatException e) {
            LOG.error("The configured 'alarmDetailsUrlPattern' ('{}') is not a valid format string, so iLert alerts" +
                    " will not include a link back to the alarm. It should contain a single '%d' for the alarm id," +
                    " and any literal percent sign must be escaped as '%%'.", pattern, e);
            return false;
        }
    }

    public String getClient() {
        return client;
    }

    /**
     * Builds the deep link back to the alarm detail page in OpenNMS.
     *
     * @return the url, or {@code null} if there is no usable pattern or no alarm id, in which case the
     *         iLert alert is sent without a link rather than not sent at all.
     */
    public String buildAlarmDetailsUrl(Integer alarmId) {
        if (!alarmDetailsUrlPatternUsable || alarmId == null) {
            return null;
        }
        return String.format(alarmDetailsUrlPattern, alarmId);
    }
}
