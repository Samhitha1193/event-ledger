package com.eventledger.gateway.client;

import com.eventledger.gateway.dto.AccountTransactionRequest;
import com.eventledger.gateway.dto.BalanceResponse;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.filter.TraceIdFilter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

@Component
public class AccountServiceClient {

    private final RestClient restClient;

    public AccountServiceClient(
            RestClient.Builder builder,
            @Value("${account.service.url}") String baseUrl,
            @Value("${account.service.connect-timeout-ms:2000}") long connectTimeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));

        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    @TimeLimiter(name = "accountService", fallbackMethod = "applyTransactionFallback")
    public CompletableFuture<Void> applyTransaction(EventRequest req) {
        // Capture trace ID on the request thread before entering the async boundary —
        // MDC is thread-local so it is invisible to the ForkJoinPool thread below.
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);

        AccountTransactionRequest body = new AccountTransactionRequest(
                req.eventId(),
                req.type().name(),
                req.amount(),
                req.currency(),
                req.eventTimestamp()
        );
        return CompletableFuture.supplyAsync(() -> {
            restClient.post()
                    .uri("/accounts/{id}/transactions", req.accountId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (traceId != null) h.set(TraceIdFilter.TRACE_HEADER, traceId); })
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    CompletableFuture<Void> applyTransactionFallback(EventRequest req, Throwable t) {
        return CompletableFuture.failedFuture(toUnavailable(t));
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "getBalanceFallback")
    @TimeLimiter(name = "accountService", fallbackMethod = "getBalanceFallback")
    public CompletableFuture<BalanceResponse> getBalance(String accountId) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return CompletableFuture.supplyAsync(() ->
                restClient.get()
                        .uri("/accounts/{id}/balance", accountId)
                        .headers(h -> { if (traceId != null) h.set(TraceIdFilter.TRACE_HEADER, traceId); })
                        .retrieve()
                        .body(BalanceResponse.class)
        );
    }

    CompletableFuture<BalanceResponse> getBalanceFallback(String accountId, Throwable t) {
        return CompletableFuture.failedFuture(toUnavailable(t));
    }

    public static <T> T block(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    private ResponseStatusException toUnavailable(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Account Service circuit breaker is open");
        }
        if (t instanceof TimeoutException) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Account Service call timed out");
        }
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Account Service unavailable");
    }
}
