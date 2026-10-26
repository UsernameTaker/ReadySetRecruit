package org.readysetrecruit.dto;

import jakarta.validation.constraints.*;
import org.readysetrecruit.model.Role;
import org.readysetrecruit.model.User;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank @Size(max = 100) String name,
            @NotNull Role role,
            // student confirms they are 13+ or have a parent's permission
            boolean ageConfirmed) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record ProfileUpdate(
            @NotBlank @Size(max = 100) String name,
            @Min(0) @Max(12) Integer grade,
            @Pattern(regexp = "^$|^\\d{5}$", message = "must be a 5-digit ZIP") String zip,
            @Email String parentEmail) {}

    public record UserDto(Long id, String email, String name, Role role, Integer grade, String zip, String parentEmail) {
        public static UserDto of(User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getGrade(), u.getZip(), u.getParentEmail());
        }
    }

    public record AuthResponse(String token, UserDto user) {}
}
