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

package org.opennms.integrations.ilert.shell;

import java.util.List;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.opennms.ilert.client.api.IlertEvent;
import org.opennms.integration.api.v1.dao.AlarmDao;
import org.opennms.integration.api.v1.model.Alarm;
import org.opennms.integrations.ilert.IlertForwarder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Karaf command for trying a JEXL filter against the alarms currently in the database, so an
 * expression can be checked before it is committed to a service's configuration.
 */
@Command(scope = "opennms-ilert", name = "eval-jexl", description = "Evaluate a JEXL expression for the ilert plugin")
@Service
public class JEXLEval implements Action {

    @Reference
    private AlarmDao alarmDao;

    @Argument(required = true)
    private String expression;

    @Option(name = "-p", aliases = "--topayload", description = "Show the ilert event payload each matching alarm would produce")
    private boolean toPayload;

    @Option(name = "-c", aliases = "--count", description = "Report only how many alarms matched")
    private boolean onlyCount;

    @Option(name = "-a", aliases = "--alarm-id", description = "Evaluate the expression against a single alarm, by id")
    private Integer alarmId;

    @Override
    public Object execute() throws JsonProcessingException {
        final JexlExpression compiled = new JexlBuilder()
                .permissions(JexlPermissions.UNRESTRICTED)
                .create()
                .createExpression(expression);

        final List<Alarm> alarms = alarmDao.getAlarms();
        if (alarms.isEmpty()) {
            System.out.println("There are no alarms to evaluate.");
            return null;
        }

        if (toPayload && onlyCount) {
            System.out.println("'-p' and '-c' contradict each other; ignoring '-p'.");
            toPayload = false;
        }

        if (alarmId != null) {
            evaluateOne(compiled, alarms);
        } else {
            evaluateAll(compiled, alarms);
        }
        return null;
    }

    /**
     * Reports how a single alarm is judged. Useful for the usual question - "why is this specific alarm
     * not reaching ilert?" - which scanning every alarm answers only indirectly.
     */
    private void evaluateOne(JexlExpression compiled, List<Alarm> alarms) throws JsonProcessingException {
        final Alarm alarm = alarms.stream()
                .filter(candidate -> alarmId.equals(candidate.getId()))
                .findFirst()
                .orElse(null);

        if (alarm == null) {
            System.out.printf("There is no alarm with id %d among the %d alarm(s) present.%n", alarmId, alarms.size());
            return;
        }

        final boolean matched = IlertForwarder.testAlarmAgainstExpression(compiled, alarm);
        System.out.printf("Alarm %d has reduction key '%s'.%n", alarmId, alarm.getReductionKey());
        System.out.printf("The expression evaluates to %s, so this alarm would %s.%n",
                matched, matched ? "be forwarded" : "not be forwarded");
        if (matched && toPayload) {
            printPayload(alarm);
        }
    }

    private void evaluateAll(JexlExpression compiled, List<Alarm> alarms) throws JsonProcessingException {
        int matches = 0;
        for (Alarm alarm : alarms) {
            if (!IlertForwarder.testAlarmAgainstExpression(compiled, alarm)) {
                continue;
            }
            matches++;
            if (onlyCount) {
                continue;
            }
            System.out.println("MATCHED: " + alarm);
            if (toPayload) {
                printPayload(alarm);
            }
        }
        System.out.printf("%n%d of %d alarm(s) matched.%n", matches, alarms.size());
    }

    private static void printPayload(Alarm alarm) throws JsonProcessingException {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        final IlertEvent event = IlertForwarder.createEvent(alarm, new IlertEvent());
        System.out.printf("ilert payload:%n%s%n%n", mapper.writeValueAsString(event));
    }
}