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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A request body for the ilert Events API (POST https://api.ilert.com/api/events).
 *
 * The ilert payload is flat - there is no nested "payload" object; summary, details,
 * priority and the custom details all live at the top level.
 */
@JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IlertEvent {

    /**
     * The fixed top-level fields of the payload. The flattened custom details are dropped
     * when they use one of these names, so an event parameter cannot overwrite them.
     */
    private static final Set<String> RESERVED_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "eventType", "summary", "details", "alertKey", "priority", "links")));

    /**
     * The type of event: ALERT, ACCEPT or RESOLVE. Required.
     */
    @JsonProperty("eventType")
    private IlertEventType eventType;

    /**
     * A brief text summary used as the alert title. Required for ALERT events.
     */
    @JsonProperty("summary")
    private String summary;

    /**
     * A larger description of the alert. Markdown is supported and rendered in the ilert UI.
     */
    @JsonProperty("details")
    private String details;

    /**
     * Key used to deduplicate/group events and to reference the open alert on ACCEPT/RESOLVE.
     */
    @JsonProperty("alertKey")
    private String alertKey;

    /**
     * HIGH (with escalation) or LOW (no escalation).
     */
    @JsonProperty("priority")
    private IlertPriority priority;

    /**
     * Deep links into other tools, rendered in the ilert web/app UI.
     */
    @JsonProperty("links")
    private List<IlertEventLink> links = new ArrayList<>();

    /**
     * Additional fields used for templating, sharing or storing information. These are
     * written flat at the top level of the payload rather than nested under a wrapper.
     */
    private Map<String, Object> customDetails = new LinkedHashMap<>();

    public IlertEventType getEventType() {
        return eventType;
    }

    public void setEventType(IlertEventType eventType) {
        this.eventType = eventType;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setSummary(String summary, int maxLength) {
        this.summary = summary.substring(0, Math.min(summary.length(), maxLength));
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getAlertKey() {
        return alertKey;
    }

    public void setAlertKey(String alertKey) {
        this.alertKey = alertKey;
    }

    public IlertPriority getPriority() {
        return priority;
    }

    public void setPriority(IlertPriority priority) {
        this.priority = priority;
    }

    public List<IlertEventLink> getLinks() {
        return links;
    }

    public void setLinks(List<IlertEventLink> links) {
        this.links = links;
    }

    @JsonIgnore
    public Map<String, Object> getCustomDetails() {
        return customDetails;
    }

    public void setCustomDetails(Map<String, Object> customDetails) {
        this.customDetails = customDetails;
    }

    /**
     * Serializes the custom details as top-level fields of the payload.
     */
    @JsonAnyGetter
    public Map<String, Object> getFlattenedCustomDetails() {
        final Map<String, Object> flattened = new LinkedHashMap<>();
        customDetails.forEach((k, v) -> {
            if (RESERVED_KEYS.contains(k)) {
                // Emitting this would produce a duplicate JSON key and could overwrite the field
                return;
            }
            flattened.put(k, v);
        });
        return flattened;
    }
}
