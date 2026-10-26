package org.readysetrecruit.dto;

import jakarta.validation.constraints.*;
import org.readysetrecruit.model.*;

import java.time.LocalDate;
import java.util.Set;

public final class TeamDtos {
    private TeamDtos() {}

    public record TeamSummary(
            Long id, String name, String teamNumber, TeamType type, Set<String> programs,
            String city, String state, String zip, Double distanceMiles,
            Integer minGrade, Integer maxGrade, boolean recruiting, boolean claimed,
            String websiteUrl, String joinUrl, boolean hasOpenApplication, String blurb) {}

    public record FormSummary(boolean acceptingApplications, String title, LocalDate deadline,
                              Set<Integer> eligibleGrades, int questionCount) {}

    public record TeamDetail(
            Long id, String name, String teamNumber, TeamType type, Set<String> programs,
            String description, String schoolName, String city, String state, String zip,
            String websiteUrl, boolean websiteAutoFound, WebsiteStatus websiteStatus,
            String websiteTitle, String websiteDescription, String joinUrl,
            String contactEmail, String meetingInfo, String costInfo,
            Integer minGrade, Integer maxGrade, boolean recruiting, boolean claimed, TeamSource source,
            FormSummary form) {}

    public record TeamUpsert(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 20) String teamNumber,
            @NotNull TeamType type,
            Set<@Size(max = 30) String> programs,
            @Size(max = 4000) String description,
            @Size(max = 200) String schoolName,
            @Size(max = 100) String city,
            @Size(max = 2) String state,
            @NotBlank @Pattern(regexp = "^\\d{5}$", message = "must be a 5-digit ZIP") String zip,
            @Size(max = 500) String websiteUrl,
            @Email String contactEmail,
            @Size(max = 1000) String meetingInfo,
            @Size(max = 200) String costInfo,
            @Min(0) @Max(12) Integer minGrade,
            @Min(0) @Max(12) Integer maxGrade,
            boolean recruiting) {}

    public record ClaimCreate(@Size(max = 2000) String message) {}

    public record ClaimDto(Long id, Long teamId, String teamName, String userName, String userEmail,
                           String message, ClaimStatus status, java.time.Instant createdAt) {
        public static ClaimDto of(ClaimRequest c) {
            return new ClaimDto(c.getId(), c.getTeam().getId(), c.getTeam().getName(), c.getUser().getName(),
                    c.getUser().getEmail(), c.getMessage(), c.getStatus(), c.getCreatedAt());
        }
    }
}
