package org.readysetrecruit.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.readysetrecruit.model.ApplicationForm;
import org.readysetrecruit.model.FormQuestion;
import org.readysetrecruit.model.QuestionType;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class FormDtos {
    private FormDtos() {}

    public record QuestionDto(
            Long id,
            @NotBlank @Size(max = 1000) String prompt,
            @Size(max = 1000) String helpText,
            @NotNull QuestionType type,
            boolean required,
            @Min(1) @Max(5000) Integer wordLimit,
            @Size(max = 2000) String options) {
        public static QuestionDto of(FormQuestion q) {
            return new QuestionDto(q.getId(), q.getPrompt(), q.getHelpText(), q.getType(), q.isRequired(),
                    q.getWordLimit(), q.getOptions());
        }
    }

    public record FormDto(Long id, Long teamId, String teamName, String title, String intro, boolean open,
                          boolean acceptingApplications, LocalDate deadline, Set<Integer> eligibleGrades,
                          List<QuestionDto> questions) {
        public static FormDto of(ApplicationForm f) {
            return new FormDto(f.getId(), f.getTeam().getId(), f.getTeam().getName(), f.getTitle(), f.getIntro(),
                    f.isOpen(), f.isAcceptingApplications(), f.getDeadline(), f.getEligibleGrades(),
                    f.getQuestions().stream().map(QuestionDto::of).toList());
        }
    }

    public record FormUpsert(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String intro,
            boolean open,
            LocalDate deadline,
            Set<@Min(0) @Max(12) Integer> eligibleGrades,
            @NotNull @Size(max = 50) List<@Valid QuestionDto> questions) {}
}
