package org.readysetrecruit.web;

import jakarta.validation.Valid;
import org.readysetrecruit.config.CurrentUser;
import org.readysetrecruit.dto.AuthDtos.*;
import org.readysetrecruit.repo.UserRepository;
import org.readysetrecruit.service.AuthService;
import org.readysetrecruit.config.ApiException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final UserRepository users;

    public AuthController(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) { return auth.register(req); }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) { return auth.login(req); }

    @GetMapping("/me")
    public UserDto me() {
        return users.findById(CurrentUser.get().getId()).map(UserDto::of).orElseThrow(() -> ApiException.notFound("User"));
    }

    @PutMapping("/me")
    public UserDto updateMe(@Valid @RequestBody ProfileUpdate req) { return auth.updateProfile(CurrentUser.get(), req); }
}
