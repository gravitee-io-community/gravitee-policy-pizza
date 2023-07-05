/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.pizza;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.http.MediaType;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.gravitee.policy.pizza.configuration.PizzaPolicyConfiguration;
import io.gravitee.policy.pizza.exceptions.NotStringArrayException;
import io.gravitee.policy.pizza.exceptions.PineappleForbiddenException;
import io.gravitee.policy.pizza.exceptions.PizzaProcessingException;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.functions.Function;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Yann TAVERNIER (yann.tavernier at graviteesource.com)
 * @author GraviteeSource Team
 */
@Slf4j
public class PizzaPolicy implements Policy {

    public static final String X_PIZZA_HEADER_TOPPING = "x-pizza-topping";
    public static final String PIZZA_ERROR_KEY = "PIZZA_ERROR";
    public static final String ERROR_PROCESSING_PIZZA = "Error processing pizza";
    public static final String X_PIZZA_HEADER = "X-Pizza";
    public static final String NOT_CREATED = "not-created";
    public static final String CREATED = "created";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PizzaPolicyConfiguration configuration;

    @Override
    public String id() {
        return "pizza-factory";
    }

    public PizzaPolicy(PizzaPolicyConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public Completable onRequest(HttpExecutionContext ctx) {
        return ctx
            .request()
            .onBody(maybeBody ->
                maybeBody
                    // If no body, then use an empty buffer
                    .defaultIfEmpty(Buffer.buffer())
                    // Create a pizza from body and headers
                    .flatMapMaybe(body -> createPizza(body, ctx.request().headers()))
                    // If no pizza has been created, then handle the case
                    .switchIfEmpty(handleNoPizza(ctx.request().headers()))
                    // Manage errors
                    .onErrorResumeNext(handleError(ctx, true))
            );
    }

    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return ctx
            .response()
            .onBody(maybeBody ->
                maybeBody
                    // If no body, then use an empty buffer
                    .defaultIfEmpty(Buffer.buffer())
                    // Create a pizza from body and headers
                    .flatMapMaybe(body -> createPizza(body, ctx.response().headers()))
                    // If no pizza has been created, then handle the case
                    .switchIfEmpty(handleNoPizza(ctx.response().headers()))
                    // Manage errors
                    .onErrorResumeNext(handleError(ctx, false))
            );
    }

    /**
     * Create a pizza according to toppings from body and headers. Crust and sauce are coming from configuration of the policy
     * @param body the request or response body
     * @param headers the request or response headers
     * @return a Maybe.empty() if no topping provided, a Maybe.just(createdPizza) if there are toppings.
     * @throws IOException or RuntimeException that will be managed by the caller.
     */
    private Maybe<Buffer> createPizza(Buffer body, HttpHeaders headers) throws IOException {
        final Set<String> toppings = extractToppings(body, headers);

        if (toppings.isEmpty()) {
            return Maybe.empty();
        }
        verifyPineapple(toppings);

        final Buffer pizzaObject = createPizzaObject(toppings, headers);

        return Maybe.just(pizzaObject);
    }

    /**
     * Extract toppings from body or headers
     * @param body
     * @param headers
     * @return a Set of toppings
     * @throws IOException
     */
    private Set<String> extractToppings(Buffer body, HttpHeaders headers) throws IOException {
        final Set<String> headersTopping = toppingsFromHeaders(headers);
        final Set<String> bodyToppings = toppingsFromBody(body);
        return Stream.concat(headersTopping.stream(), bodyToppings.stream()).collect(Collectors.toSet());
    }

    private Set<String> toppingsFromHeaders(HttpHeaders headers) {
        return new HashSet<>(headers.getAll(X_PIZZA_HEADER_TOPPING));
    }

    /**
     * Extract toppings from body. Body should be an array of strings, else it throws
     * @param body
     * @return an empty set if body is empty, else the extract list of toppings
     * @throws NotStringArrayException
     */
    private Set<String> toppingsFromBody(Buffer body) throws IOException {
        if (body.length() == 0) {
            return Set.of();
        }
        final JsonNode json = MAPPER.readTree(body.getBytes());
        if (!json.isArray()) {
            throw new NotStringArrayException();
        }

        final Set<String> toppings = new HashSet<>();
        for (JsonNode toppingNode : json) {
            if (!toppingNode.isTextual()) {
                throw new NotStringArrayException();
            }
            toppings.add(toppingNode.asText());
        }
        return toppings;
    }

    /**
     * Throws if pineapple topping is forbidden and the list of toppings contains pineapple or ananas
     * @param toppings to verify
     * @throws io.gravitee.policy.pizza.exceptions.PineappleForbiddenException
     */
    private void verifyPineapple(Set<String> toppings) {
        if (
            configuration.isPineappleForbidden() &&
            toppings.stream().anyMatch(topping -> "ananas".equalsIgnoreCase(topping) || "pineapple".equalsIgnoreCase(topping))
        ) {
            throw new PineappleForbiddenException();
        }
    }

    /**
     * Build a pizza Buffer from configuration and extracted toppings
     * @param toppings
     * @param headers
     * @return a pizza in a buffer
     */
    private Buffer createPizzaObject(Set<String> toppings, HttpHeaders headers) {
        final Pizza pizza = Pizza.builder().sauce(configuration.getSauce()).crust(configuration.getCrust()).toppings(toppings).build();

        try {
            final String jsonPizza = MAPPER.writeValueAsString(pizza);
            headers.set(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(jsonPizza.length()));
            headers.set(HttpHeaderNames.CONTENT_TYPE, MediaType.APPLICATION_JSON);
            headers.set(X_PIZZA_HEADER, CREATED);
            return Buffer.buffer(jsonPizza);
        } catch (JsonProcessingException e) {
            throw new PizzaProcessingException(ERROR_PROCESSING_PIZZA);
        }
    }

    /**
     * If no pizza created, then return an empty buffer and add a particular header.
     * ⚠️ The header is added as part of the reactive chain
     * @param headers
     * @return
     */
    private static Maybe<Buffer> handleNoPizza(HttpHeaders headers) {
        return Maybe.fromCallable(() -> {
            headers.add(X_PIZZA_HEADER, NOT_CREATED);
            return Buffer.buffer();
        });
    }

    /**
     * Handle errors that might have been thrown during the pizza creation process
     * Manage the particular case of PineappleForbiddenException exception on request.
     * @param ctx
     * @param isOnRequest
     * @return
     */
    private Function<Throwable, Maybe<Buffer>> handleError(HttpExecutionContext ctx, boolean isOnRequest) {
        return err -> {
            log.warn("It was not possible to create the pizza because: {}", err.getMessage());
            if (isOnRequest && err instanceof PineappleForbiddenException) {
                return ctx.interruptBodyWith(
                    new ExecutionFailure(HttpStatusCode.NOT_ACCEPTABLE_406).key(PIZZA_ERROR_KEY).message(err.getMessage())
                );
            }
            return ctx.interruptBodyWith(
                new ExecutionFailure(isOnRequest ? HttpStatusCode.BAD_REQUEST_400 : HttpStatusCode.INTERNAL_SERVER_ERROR_500)
                    .key(PIZZA_ERROR_KEY)
                    .message(err.getMessage())
            );
        };
    }
}
