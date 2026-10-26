package org.readysetrecruit.service;

import org.readysetrecruit.config.ApiException;
import org.readysetrecruit.config.JwtService;
import org.readysetrecruit.dto.AuthDtos.*;
import org.readysetrecruit.model.Role;
import org.readysetrecruit.model.User;
import org.readysetrecruit.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final Set<String> adminEmails;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       @Value("${app.admin-emails}") String adminEmails) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.adminEmails = Arrays.stream(adminEmails.split(","))
                .map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        if (req.role() == Role.ADMIN) throw ApiException.badRequest("Invalid account type");
        if (req.role() == Role.STUDENT && !req.ageConfirmed())
            throw ApiException.badRequest("Students must be 13 or older, or have a parent's permission to sign up");
        if (users.existsByEmailIgnoreCase(email)) throw ApiException.conflict("An account with that email already exists");

        User u = new User();
        u.setEmail(email);
        u.setName(req.name().trim());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(adminEmails.contains(email) ? Role.ADMIN : req.role());
        users.save(u);
        return new AuthResponse(jwt.issue(u), UserDto.of(u));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User u = users.findByEmailIgnoreCase(req.email().trim())
                .filter(x -> encoder.matches(req.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Wrong email or password"));
        return new AuthResponse(jwt.issue(u), UserDto.of(u));
    }

    @Transactional
    public UserDto updateProfile(User current, ProfileUpdate req) {
        User u = users.findById(current.getId()).orElseThrow(() -> ApiException.notFound("User"));
        u.setName(req.name().trim());
        u.setGrade(req.grade());
        u.setZip(TextUtil.trimToNull(req.zip()));
        u.setParentEmail(TextUtil.trimToNull(req.parentEmail()));
        return UserDto.of(u);
    }
}
