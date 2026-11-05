package com.example.orders.adapter.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Correlation context for synchronous HTTP processing. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Request-ID";
    public static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static final String MDC_KEY = "requestId";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        String requestId = selectId(request);
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        response.setHeader(HEADER_NAME, requestId);

        String previousId = MDC.get(MDC_KEY);
        try {
            MDC.put(MDC_KEY, requestId);
            chain.doFilter(request, response);
        } finally {
            if (previousId == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousId);
            }
        }
    }

    private String selectId(HttpServletRequest request) {
        var values = request.getHeaders(HEADER_NAME);
        if (values != null && values.hasMoreElements()) {
            String value = values.nextElement();
            if (!values.hasMoreElements() && value != null && value.length() <= 64
                    && VALID_ID.matcher(value).matches()) {
                return value;
            }
        }
        return UUID.randomUUID().toString();
    }
}
