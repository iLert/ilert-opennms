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

/**
 * The type of event to send to the iLert Events API.
 *
 * The enum names match the values expected by iLert exactly, so default
 * Jackson enum serialization produces the correct wire format.
 */
public enum IlertEventType {
    /** Create (or append to) an alert. */
    ALERT,
    /** Accept an existing open alert. */
    ACCEPT,
    /** Resolve an existing open alert. */
    RESOLVE
}
