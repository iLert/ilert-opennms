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

package org.opennms.ilert.client.api;

import org.opennms.integration.api.v1.model.Severity;

/**
 * ilert alert priority. HIGH triggers the escalation policy, LOW does not.
 *
 * The enum names match the values expected by ilert exactly.
 */
public enum IlertPriority {
    HIGH,
    LOW;

    /**
     * Maps an OpenNMS alarm severity onto an ilert priority.
     *
     * CRITICAL and MAJOR alarms escalate (HIGH); everything else is LOW.
     */
    public static IlertPriority fromOnmsSeverity(Severity severity) {
        if (severity == null) {
            return LOW;
        }
        return switch (severity) {
            case CRITICAL, MAJOR -> HIGH;
            default -> LOW;
        };
    }
}
