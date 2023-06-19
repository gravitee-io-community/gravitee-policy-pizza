package io.gravitee.policy.pizza;

import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.reactivex.rxjava3.core.Completable;

/**
 * @author Yann TAVERNIER (yann.tavernier at graviteesource.com)
 * @author GraviteeSource Team
 */
public class PizzaPolicy implements Policy {

    @Override
    public String id() {
        return "pizza-factory";
    }

    @Override
    public Completable onRequest(HttpExecutionContext ctx) {
        return Policy.super.onRequest(ctx);
    }

    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return Policy.super.onResponse(ctx);
    }
}
