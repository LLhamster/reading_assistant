package com.example.httpreading.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.*;
import java.io.IOException;
import java.util.UUID;

@Order(1)
public class RequestIdFilter implements Filter {
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String REQUEST_ID_ATTR = "reqId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 优先取请求头中的ID，没有则生成新的
        String reqId = httpRequest.getHeader(REQUEST_ID_HEADER);
        if (reqId == null || reqId.isEmpty()) {
            reqId = "req-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // 存入 MDC，后续日志可以获取
        org.slf4j.MDC.put(REQUEST_ID_ATTR, reqId);
        httpResponse.setHeader(REQUEST_ID_HEADER, reqId);

        try {
            chain.doFilter(request, response);
        } finally {
            org.slf4j.MDC.remove(REQUEST_ID_ATTR);
        }
    }
}
