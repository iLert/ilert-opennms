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

package org.opennms.ilert.client.impl;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.opennms.ilert.client.api.IlertClient;
import org.opennms.ilert.client.api.IlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Posts events to one ilert alert source over HTTP.
 *
 * <p>Sends are asynchronous: {@link #sendEvent} hands the request to OkHttp's dispatcher and returns
 * a future, so a slow or unreachable ilert never blocks the caller. Callers decide whether a failure
 * is worth retrying.
 */
public class IlertClientImpl implements IlertClient {
    private static final Logger LOG = LoggerFactory.getLogger(IlertClientImpl.class);

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** Kept well under the caller's own send timeout so the HTTP layer is what gives up first. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private static final String USER_AGENT = "opennms-ilert-plugin";

    /** Shown instead of a response body when ilert rejects a request without explaining why. */
    private static final String NO_BODY = "(empty)";

    /**
     * The alert source events endpoint to post to, exactly as copied out of ilert. It carries the
     * integration key in its own path, which is why neither this url nor anything derived from it is
     * ever written to the log.
     */
    private final String eventsApiUrl;

    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public IlertClientImpl(String eventsApiUrl) {
        if (eventsApiUrl == null || eventsApiUrl.isBlank()) {
            throw new IllegalArgumentException("An ilert events url is required.");
        }
        this.eventsApiUrl = eventsApiUrl.trim();
        // This OkHttp release predates the Duration overloads, so the timeouts go in as millis.
        final long timeoutMillis = HTTP_TIMEOUT.toMillis();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public CompletableFuture<Void> sendEvent(IlertEvent event) {
        final CompletableFuture<Void> outcome = new CompletableFuture<>();

        final Request request;
        try {
            request = toRequest(event);
        } catch (JsonProcessingException e) {
            // Reported through the future rather than thrown: sendEvent is called straight from an
            // alarm lifecycle callback, which must not have exceptions escape into it.
            outcome.completeExceptionally(e);
            return outcome;
        }

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LOG.debug("Could not reach ilert to post an event.", e);
                outcome.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeMe = response) {
                    final int status = closeMe.code();
                    LOG.debug("ilert answered with status {}", status);
                    if (closeMe.isSuccessful()) {
                        outcome.complete(null);
                    } else {
                        outcome.completeExceptionally(new IOException(
                                "ilert rejected the event with status " + status + ": " + describeBody(closeMe)));
                    }
                }
            }
        });

        return outcome;
    }

    private Request toRequest(IlertEvent event) throws JsonProcessingException {
        final String payload = mapper.writeValueAsString(event);
        LOG.debug("Posting ilert event: {}", payload);
        return new Request.Builder()
                .url(eventsApiUrl)
                // Content-Type comes from the request body itself, so it is not set here.
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", USER_AGENT)
                .post(RequestBody.create(JSON, payload))
                .build();
    }

    /**
     * Reads an unsuccessful response body so it can go in the failure message. A body that cannot be
     * read is not worth failing over - the status code is the useful part either way.
     */
    private static String describeBody(Response response) {
        final ResponseBody body = response.body();
        if (body == null) {
            return NO_BODY;
        }
        try {
            final String text = body.string();
            return text.isBlank() ? NO_BODY : text;
        } catch (IOException e) {
            LOG.debug("Could not read the body of an unsuccessful ilert response.", e);
            return NO_BODY;
        }
    }

    @Override
    public void close() {
        // OkHttp holds a dispatcher thread pool and a connection pool, both of which outlive a client
        // that is simply dropped, so they have to be released explicitly.
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}