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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A link rendered alongside an alert in the iLert web and mobile apps - used here to point back at the
 * alarm in OpenNMS.
 *
 * @param href the target url
 * @param text the label shown for the link
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IlertEventLink(@JsonProperty("href") String href, @JsonProperty("text") String text) {
}
