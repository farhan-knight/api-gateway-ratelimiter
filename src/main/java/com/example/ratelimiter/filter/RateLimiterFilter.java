package com.example.ratelimiter.filter;

import com.example.ratelimiter.service.RateLimiterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;


@Slf4j
@Component
public class RateLimiterFilter extends AbstractGatewayFilterFactory<RateLimiterFilter.Config> {

    private final RateLimiterService rateLimiterService;

    public RateLimiterFilter(RateLimiterService rateLimiterService) {
        super(Config.class);
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public Config newConfig() {
        return new Config();
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            ServerHttpRequest request = exchange.getRequest();
            ServerHttpResponse response = exchange.getResponse();
            String clientId = getClientId(request);

            if (!rateLimiterService.isAllowed(clientId)) {

                response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                addRateLimitHeaders(response, clientId);

                String errorBody = String.format(
                        "{\"error\":\"Rate limit exceeded\",\"clientId\":\"%s\",\"algorithm\":\"%s\"}",
                        clientId, rateLimiterService.getActiveAlgorithm()
                );

                log.info("RATE LIMITED | client={} | algo={}",
                        clientId, rateLimiterService.getActiveAlgorithm());

                return response.writeWith(
                        Mono.just(response.bufferFactory()
                                .wrap(errorBody.getBytes(StandardCharsets.UTF_8)))
                );
            }

            return chain.filter(exchange).then(Mono.fromRunnable(() ->
                    addRateLimitHeaders(response, clientId)
            ));
        };
    }

    private void addRateLimitHeaders(ServerHttpResponse response, String clientId) {
        response.getHeaders().add("X-RateLimit-Limit",
                String.valueOf(rateLimiterService.getCapacity(clientId)));
        response.getHeaders().add("X-RateLimit-Remaining",
                String.valueOf(rateLimiterService.getAvailableTokens(clientId)));
        response.getHeaders().add("X-RateLimit-Algorithm",
                rateLimiterService.getActiveAlgorithm());
    }

    private String getClientId(ServerHttpRequest request) {
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        var remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    public static class Config {
    }
}
