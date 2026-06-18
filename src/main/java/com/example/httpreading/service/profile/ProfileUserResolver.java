package com.example.httpreading.service.profile;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class ProfileUserResolver {
    public String resolve(String requestedUserId, String sessionId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() != null
            && !"anonymousUser".equals(String.valueOf(authentication.getPrincipal()))) {
            return String.valueOf(authentication.getPrincipal());
        }
        if (requestedUserId != null && !requestedUserId.isBlank()
            && !"default_user".equals(requestedUserId)
            && !requestedUserId.startsWith("guest:")) {
            return requestedUserId.trim();
        }
        String resolvedSession = sessionId == null || sessionId.isBlank() ? "default_session" : sessionId.trim();
        return "guest:" + resolvedSession;
    }
}
