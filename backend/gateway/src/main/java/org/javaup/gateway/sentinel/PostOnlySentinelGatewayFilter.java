package org.javaup.gateway.sentinel;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Sentinel's built-in API predicates match paths; only POST belongs to our protected API groups. */
public final class PostOnlySentinelGatewayFilter extends SentinelGatewayFilter {

    public PostOnlySentinelGatewayFilter() {
        // AuthenticationGlobalFilter (-100) verifies JWT and replaces untrusted identity headers first.
        super(-90);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }
        return super.filter(exchange, chain);
    }
}
