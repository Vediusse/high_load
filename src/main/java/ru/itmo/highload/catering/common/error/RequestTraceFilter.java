package ru.itmo.highload.catering.common.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String suppliedTraceId = request.getHeader(TRACE_ID_HEADER);
        String traceId = suppliedTraceId != null && SAFE_TRACE_ID.matcher(suppliedTraceId).matches()
                ? suppliedTraceId
                : UUID.randomUUID().toString();

        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        filterChain.doFilter(request, response);
    }
}
