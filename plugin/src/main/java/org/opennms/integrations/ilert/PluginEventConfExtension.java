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

import java.util.List;

import org.opennms.integration.api.v1.config.events.EventConfExtension;
import org.opennms.integration.api.v1.config.events.EventDefinition;
import org.opennms.integration.api.xml.ClasspathEventDefinitionLoader;

/**
 * Contributes the plugin's own event definitions to the OpenNMS event configuration, so that the
 * sendEventFailed and sendEventSuccessful events it raises have somewhere to be defined.
 *
 * <p>Without this the events would still be sent, but OpenNMS would have no definition to turn them
 * into alarms with, and they would be discarded on arrival.
 */
public class PluginEventConfExtension implements EventConfExtension {

    private static final String DEFINITIONS_RESOURCE = "ilert.ext.events.xml";

    private static final ClasspathEventDefinitionLoader LOADER =
            new ClasspathEventDefinitionLoader(PluginEventConfExtension.class, DEFINITIONS_RESOURCE);

    @Override
    public List<EventDefinition> getEventDefinitions() {
        return LOADER.getEventDefinitions();
    }
}