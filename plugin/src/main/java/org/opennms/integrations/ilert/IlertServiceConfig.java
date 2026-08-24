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
import java.util.Objects;

/**
 * The configuration of a single ilert service, as read from one
 * {@code org.opennms.plugins.ilert.services-*.cfg} file.
 *
 * @param pid            the configuration pid this service was created from
 * @param url            the alert source events endpoint to post to, exactly as copied out of ilert.
 *                       Required: it carries the integration key in its path, so there is no default
 *                       and nothing to fall back to.
 * @param jexlFilter     the expression deciding which alarms this service forwards, or {@code null} to
 *                       forward none
 * @param holdDownDelay  how long an alert waits before being sent, so that alarms which clear inside
 *                       the window never page anyone. Never {@code null} - absent means
 *                       {@link Duration#ZERO}, i.e. send immediately.
 */
public record IlertServiceConfig(String pid, String url, String jexlFilter, Duration holdDownDelay) {

    public IlertServiceConfig {
        Objects.requireNonNull(pid, "pid is required");
        Objects.requireNonNull(url, "url is required");
        if (holdDownDelay == null) {
            holdDownDelay = Duration.ZERO;
        }
    }

    /**
     * Deliberately omits the url: it embeds the alert source integration key, and this ends up in log
     * files.
     */
    @Override
    public String toString() {
        return "IlertServiceConfig[pid=" + pid
                + ", jexlFilter=" + jexlFilter
                + ", holdDownDelay=" + holdDownDelay + "]";
    }
}
