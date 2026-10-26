package org.readysetrecruit.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.readysetrecruit.model.Application;
import org.readysetrecruit.model.ApplicationStatus;

import java.time.Instant;
import java.util.List;

public final class ApplicationDtos {
    private ApplicationDtos() {}

    public record AnswerDto(Long questionId, String prompt, String response) {}

    public record AnswerInput(@NotNull Long questionId, @Size(max = 20000) String response) {}

    public record ApplicationSave(
            @Min(0) @Max(12) Integer grade,
            @Email String parentEmail,
            @NotNull List<@Valid AnswerInput> answers,
            boolean submit) {}

    public record ApplicationReview(@NotNull ApplicationStatus status, @Size(max = 4000) String teamNotes) {}

    public record ApplicationDto(
            Long id, Long teamId, String teamName, ApplicationStatus status, Integer grade, String parentEmail,
            List<AnswerDto> answers, Instant createdAt, Instant updatedAt, Instant submittedAt,
            String studentName, String studentEmail, String teamNotes) {

        /** View for the student: hides the team's private notes. */
        public static ApplicationDto forStudent(Application a) {
            return build(a, null);
        }

        /** View for the team: includes private notes. */
        public static ApplicationDto forTeam(Application a) {
            return build(a, a.getTeamNotes());
        }

        private static ApplicationDto build(Application a, String notes) {
            return new ApplicationDto(a.getId(), a.getTeam().getId(), a.getTeam().getName(), a.getStatus(),
                    a.getGrade(), a.getParentEmail(),
                    a.getAnswers().stream().map(x -> new AnswerDto(x.getQuestionId(), x.getPrompt(), x.getResponse())).toList(),
                    a.getCreatedAt(), a.getUpdatedAt(), a.getSubmittedAt(),
                    a.getStudent().getName(), a.getStudent().getEmail(), notes);
        }
    }
}
