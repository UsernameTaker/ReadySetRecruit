package org.readysetrecruit.config;

import org.readysetrecruit.model.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;

public final class CurrentUser {
    private CurrentUser() {}

    public static User get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User u) return u;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Please log in");
    }
}
