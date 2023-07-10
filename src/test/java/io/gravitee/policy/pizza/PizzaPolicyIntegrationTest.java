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

import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.gravitee.apim.gateway.tests.sdk.utils.HttpClientUtils.extractHeaders;
import static io.gravitee.policy.pizza.PizzaPolicy.CREATED;
import static io.gravitee.policy.pizza.PizzaPolicy.NOT_CREATED;
import static io.gravitee.policy.pizza.PizzaPolicy.X_PIZZA_HEADER;
import static io.gravitee.policy.pizza.PizzaPolicy.X_PIZZA_HEADER_TOPPING;
import static io.gravitee.policy.pizza.exceptions.NotStringArrayException.ERROR_BODY_SHOULD_BE_AN_ARRAY_OF_STRINGS;
import static io.gravitee.policy.pizza.exceptions.PineappleForbiddenException.ERROR_PINEAPPLE_FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.apim.gateway.tests.sdk.AbstractPolicyTest;
import io.gravitee.apim.gateway.tests.sdk.annotations.DeployApi;
import io.gravitee.apim.gateway.tests.sdk.annotations.GatewayTest;
import io.gravitee.apim.gateway.tests.sdk.connector.EndpointBuilder;
import io.gravitee.apim.gateway.tests.sdk.connector.EntrypointBuilder;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.definition.model.v4.Api;
import io.gravitee.definition.model.v4.flow.Flow;
import io.gravitee.definition.model.v4.flow.step.Step;
import io.gravitee.gateway.reactor.ReactableApi;
import io.gravitee.plugin.endpoint.EndpointConnectorPlugin;
import io.gravitee.plugin.endpoint.http.proxy.HttpProxyEndpointConnectorFactory;
import io.gravitee.plugin.entrypoint.EntrypointConnectorPlugin;
import io.gravitee.plugin.entrypoint.http.proxy.HttpProxyEntrypointConnectorFactory;
import io.gravitee.policy.pizza.configuration.PizzaPolicyConfiguration;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.core.http.HttpClientRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * @author Yann TAVERNIER (yann.tavernier at graviteesource.com)
 * @author GraviteeSource Team
 */
class PizzaPolicyIntegrationTest {

    static class TestPreparer extends AbstractPolicyTest<PizzaPolicy, PizzaPolicyConfiguration> {

        @Override
        public void configureEntrypoints(Map<String, EntrypointConnectorPlugin<?, ?>> entrypoints) {
            entrypoints.putIfAbsent("http-proxy", EntrypointBuilder.build("http-proxy", HttpProxyEntrypointConnectorFactory.class));
        }

        @Override
        public void configureEndpoints(Map<String, EndpointConnectorPlugin<?, ?>> endpoints) {
            endpoints.putIfAbsent("http-proxy", EndpointBuilder.build("http-proxy", HttpProxyEndpointConnectorFactory.class));
        }
    }

    @Nested
    @GatewayTest
    @DeployApi({ "/apis/pizza-api.json", "/apis/pizza-pineapple.json" })
    class OnRequest extends TestPreparer {

        @Test
        @DisplayName("Should not create pizza when no topping provided")
        void should_not_create_pizza_on_request(HttpClient httpClient) {
            wiremock.stubFor(get("/endpoint").willReturn(ok()));

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(request -> request.rxSend())
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK_200);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(Buffer.buffer())
                .assertNoErrors();

            wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")).withHeader(X_PIZZA_HEADER, equalTo(NOT_CREATED)));
        }

        @Test
        @DisplayName("Should create pizza when toppings provided from headers")
        void should_create_pizza_with_header_toppings_on_request(HttpClient httpClient) {
            wiremock.stubFor(get("/endpoint").willReturn(ok()));

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(request -> request.putHeader(X_PIZZA_HEADER_TOPPING, List.<String>of("peperoni", "cheddar")).rxSend())
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK_200);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(Buffer.buffer())
                .assertNoErrors();

            wiremock.verify(
                1,
                getRequestedFor(urlPathEqualTo("/endpoint"))
                    .withHeader(X_PIZZA_HEADER, equalTo(CREATED))
                    .withRequestBody(equalTo("{\"crust\":\"Pan\",\"sauce\":\"TOMATO\",\"toppings\":[\"peperoni\",\"cheddar\"]}"))
            );
        }

        @Test
        @DisplayName("Should create pizza when toppings provided from body")
        void should_create_pizza_with_body_toppings_on_request(HttpClient httpClient) {
            wiremock.stubFor(get("/endpoint").willReturn(ok()));

            final JsonArray toppings = new JsonArray();
            toppings.add("peperoni");
            toppings.add("cheddar");

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(httpClientRequest -> httpClientRequest.rxSend(toppings.toString()))
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK_200);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(Buffer.buffer())
                .assertNoErrors();

            wiremock.verify(
                1,
                getRequestedFor(urlPathEqualTo("/endpoint"))
                    .withHeader(X_PIZZA_HEADER, equalTo(CREATED))
                    .withRequestBody(equalTo("{\"crust\":\"Pan\",\"sauce\":\"TOMATO\",\"toppings\":[\"peperoni\",\"cheddar\"]}"))
            );
        }

        @Test
        @DisplayName("Should create pizza when toppings provided from headers and body")
        void should_create_pizza_with_headers_and_body_toppings_on_request(HttpClient httpClient) {
            wiremock.stubFor(get("/endpoint").willReturn(ok()));

            final JsonArray toppings = new JsonArray();
            toppings.add("peperoni");
            toppings.add("cheddar");

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(httpClientRequest ->
                    httpClientRequest.putHeader(X_PIZZA_HEADER_TOPPING, List.<String>of("cheddar", "mushroom")).rxSend(toppings.toString())
                )
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK_200);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(Buffer.buffer())
                .assertNoErrors();

            wiremock.verify(
                1,
                getRequestedFor(urlPathEqualTo("/endpoint"))
                    .withHeader(X_PIZZA_HEADER, equalTo(CREATED))
                    .withRequestBody(
                        equalTo("{\"crust\":\"Pan\",\"sauce\":\"TOMATO\",\"toppings\":[\"mushroom\",\"cheddar\",\"peperoni\"]}")
                    )
            );
        }

        @Test
        @DisplayName("Should create pizza with pineapple")
        void should_create_pizza_with_pineapple(HttpClient httpClient) {
            wiremock.stubFor(get("/endpoint").willReturn(ok()));

            final JsonArray toppings = new JsonArray();
            toppings.add("peperoni");
            toppings.add("pineapple");

            httpClient
                .rxRequest(HttpMethod.GET, "/test-pineapple")
                .flatMap(httpClientRequest ->
                    httpClientRequest.putHeader(X_PIZZA_HEADER_TOPPING, List.<String>of("cheddar", "mushroom")).rxSend(toppings.toString())
                )
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK_200);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(Buffer.buffer())
                .assertNoErrors();

            wiremock.verify(
                1,
                getRequestedFor(urlPathEqualTo("/endpoint"))
                    .withHeader(X_PIZZA_HEADER, equalTo(CREATED))
                    .withRequestBody(
                        equalTo(
                            "{\"crust\":\"Pan\",\"sauce\":\"TOMATO\",\"toppings\":[\"pineapple\",\"mushroom\",\"cheddar\",\"peperoni\"]}"
                        )
                    )
            );
        }

        @Test
        @DisplayName("Should fail with 400 - BAD REQUEST when toppings are not strings")
        void should_fail_when_toppings_are_not_strings(HttpClient httpClient) {
            wiremock.stubFor(get("/endpoint").willReturn(ok()));

            // Provide a bad formatted body
            final JsonArray toppings = new JsonArray();
            toppings.add(new JsonObject().put("topping", "peperoni"));

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(httpClientRequest ->
                    httpClientRequest.putHeader(X_PIZZA_HEADER_TOPPING, List.<String>of("cheddar", "mushroom")).rxSend(toppings.toString())
                )
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.BAD_REQUEST_400);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(body -> {
                    assertThat(body).hasToString(ERROR_BODY_SHOULD_BE_AN_ARRAY_OF_STRINGS);
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(0, getRequestedFor(anyUrl()));
        }

        @Test
        @DisplayName("Should fail with 406 - NOT ACCEPTABLE if pineapple is forbidden")
        void should_fail_if_pineapple_forbidden(HttpClient httpClient) {
            wiremock.stubFor(get("/endpoint").willReturn(ok()));

            final JsonArray toppings = new JsonArray();
            toppings.add("peperoni");
            toppings.add("pineapple");

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(httpClientRequest ->
                    httpClientRequest.putHeader(X_PIZZA_HEADER_TOPPING, List.<String>of("cheddar", "mushroom")).rxSend(toppings.toString())
                )
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.NOT_ACCEPTABLE_406);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(body -> {
                    assertThat(body).hasToString(ERROR_PINEAPPLE_FORBIDDEN);
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(0, getRequestedFor(anyUrl()));
        }
    }

    @Nested
    @GatewayTest
    @DeployApi({ "/apis/pizza-api.json", "/apis/pizza-pineapple.json" })
    class OnResponse extends TestPreparer {

        /**
         * Instead of redefining a json file, we just use the same as for request, and we move the steps (policies) from request to response
         * @param api is the reactable api to modify
         * @param definitionClass is the definition class to use to verify version of api
         */
        @Override
        public void configureApi(ReactableApi<?> api, Class<?> definitionClass) {
            if (isV4Api(definitionClass)) {
                final Api definition = (Api) api.getDefinition();
                final Flow apiFlow = definition.getFlows().get(0);
                final List<Step> requestSteps = apiFlow.getRequest();
                apiFlow.setRequest(List.of());
                apiFlow.setResponse(requestSteps);
            }
        }

        @Test
        @DisplayName("Should not create pizza when no topping provided")
        void should_not_create_pizza_on_response(HttpClient httpClient) {
            wiremock.stubFor(get("/endpoint").willReturn(ok()));

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(request -> request.rxSend())
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK_200);
                    assertThat(extractHeaders(response)).contains(Map.entry(X_PIZZA_HEADER, NOT_CREATED));
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(Buffer.buffer())
                .assertNoErrors();

            wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        }

        @Test
        @DisplayName("Should create pizza when toppings provided from headers")
        void should_create_pizza_with_header_toppings_on_response(HttpClient httpClient) {
            wiremock.stubFor(get("/endpoint").willReturn(ok().withHeader(X_PIZZA_HEADER_TOPPING, "peperoni", "cheddar")));

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(HttpClientRequest::rxSend)
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK_200);
                    assertThat(response.headers().get(X_PIZZA_HEADER)).isEqualTo(CREATED);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(body -> {
                    assertThat(body).hasToString("{\"crust\":\"Pan\",\"sauce\":\"TOMATO\",\"toppings\":[\"peperoni\",\"cheddar\"]}");
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        }

        @Test
        @DisplayName("Should create pizza when toppings provided from body")
        void should_create_pizza_with_body_toppings_on_response(HttpClient httpClient) {
            final JsonArray toppings = new JsonArray();
            toppings.add("peperoni");
            toppings.add("cheddar");

            wiremock.stubFor(get("/endpoint").willReturn(ok(toppings.toString())));

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(HttpClientRequest::rxSend)
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK_200);
                    assertThat(response.headers().get(X_PIZZA_HEADER)).isEqualTo(CREATED);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(body -> {
                    assertThat(body).hasToString("{\"crust\":\"Pan\",\"sauce\":\"TOMATO\",\"toppings\":[\"peperoni\",\"cheddar\"]}");
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        }

        @Test
        @DisplayName("Should create pizza when toppings provided from headers and body")
        void should_create_pizza_with_headers_and_body_toppings_on_response(HttpClient httpClient) {
            final JsonArray toppings = new JsonArray();
            toppings.add("peperoni");
            toppings.add("cheddar");

            wiremock.stubFor(
                get("/endpoint").willReturn(ok(toppings.toString()).withHeader(X_PIZZA_HEADER_TOPPING, "cheddar", "mushroom"))
            );

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(HttpClientRequest::rxSend)
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK_200);
                    assertThat(response.headers().get(X_PIZZA_HEADER)).isEqualTo(CREATED);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(body -> {
                    assertThat(body)
                        .hasToString("{\"crust\":\"Pan\",\"sauce\":\"TOMATO\",\"toppings\":[\"mushroom\",\"cheddar\",\"peperoni\"]}");
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        }

        @Test
        @DisplayName("Should create pizza with pineapple")
        void should_create_pizza_with_pineapple_on_response(HttpClient httpClient) {
            final JsonArray toppings = new JsonArray();
            toppings.add("peperoni");
            toppings.add("pineapple");

            wiremock.stubFor(
                get("/endpoint").willReturn(ok(toppings.toString()).withHeader(X_PIZZA_HEADER_TOPPING, "cheddar", "mushroom"))
            );

            httpClient
                .rxRequest(HttpMethod.GET, "/test-pineapple")
                .flatMap(HttpClientRequest::rxSend)
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK_200);
                    assertThat(response.headers().get(X_PIZZA_HEADER)).isEqualTo(CREATED);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(body -> {
                    assertThat(body)
                        .hasToString(
                            "{\"crust\":\"Pan\",\"sauce\":\"TOMATO\",\"toppings\":[\"pineapple\",\"mushroom\",\"cheddar\",\"peperoni\"]}"
                        );
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        }

        @Test
        @DisplayName("Should fail with 500 - INTERNAL SERVER ERROR when toppings are not strings")
        void should_fail_when_toppings_are_not_strings(HttpClient httpClient) {
            // Provide a bad formatted body
            final JsonArray toppings = new JsonArray();
            toppings.add(new JsonObject().put("topping", "peperoni"));

            wiremock.stubFor(
                get("/endpoint").willReturn(ok(toppings.toString()).withHeader(X_PIZZA_HEADER_TOPPING, "cheddar", "mushroom"))
            );

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(HttpClientRequest::rxSend)
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.INTERNAL_SERVER_ERROR_500);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(body -> {
                    assertThat(body).hasToString(ERROR_BODY_SHOULD_BE_AN_ARRAY_OF_STRINGS);
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        }

        @Test
        @DisplayName("Should fail with 500 - INTERNAL SERVER ERROR if pineapple is forbidden")
        void should_fail_if_pineapple_forbidden(HttpClient httpClient) {
            final JsonArray toppings = new JsonArray();
            toppings.add("peperoni");
            toppings.add("pineapple");

            wiremock.stubFor(
                get("/endpoint").willReturn(ok(toppings.toString()).withHeader(X_PIZZA_HEADER_TOPPING, "cheddar", "mushroom"))
            );

            httpClient
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(HttpClientRequest::rxSend)
                .flatMap(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatusCode.INTERNAL_SERVER_ERROR_500);
                    return response.body();
                })
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(body -> {
                    assertThat(body).hasToString(ERROR_PINEAPPLE_FORBIDDEN);
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        }
    }
}
