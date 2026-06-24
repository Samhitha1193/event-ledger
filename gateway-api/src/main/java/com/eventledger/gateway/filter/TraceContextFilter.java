package com.eventledger.gateway.filter;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter implements Filter {

    private static final TextMapGetter<HttpServletRequest> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return () -> carrier.getHeaderNames().asIterator();
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier.getHeader(key);
        }
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        // Extract the incoming W3C traceparent into a new OTel context and make it current.
        // ServerHttpObservationFilter runs after this; its DefaultTracingObservationHandler
        // calls OtelTracer.nextSpan() → SdkSpanBuilder.startSpan() which reads Context.current()
        // for a parent — so the new span inherits the incoming trace ID.
        // When no traceparent is present, extract() returns Context.root() unchanged and a
        // fresh root span is generated as normal.
        Context extractedContext = W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), httpRequest, GETTER);
        try (Scope scope = extractedContext.makeCurrent()) {
            chain.doFilter(request, response);
        }
    }
}
