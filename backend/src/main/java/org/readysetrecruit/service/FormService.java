package org.readysetrecruit.service;

import org.readysetrecruit.config.ApiException;
import org.readysetrecruit.dto.FormDtos.*;
import org.readysetrecruit.model.*;
import org.readysetrecruit.repo.ApplicationFormRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FormService {
    private final ApplicationFormRepository forms;
    private final TeamService teamService;

    public FormService(ApplicationFormRepository forms, TeamService teamService) {
        this.forms = forms;
        this.teamService = teamService;
    }

    // public view of a team's form (students can read questions before logging in)
    @Transactional(readOnly = true)
    public FormDto publicForm(Long teamId) {
        return forms.findByTeamId(teamId).map(FormDto::of)
                .orElseThrow(() -> ApiException.notFound("Application form"));
    }

    // the team's own form for editing; returns an empty, unsaved template if none exists yet
    @Transactional(readOnly = true)
    public FormDto formForEditing(User user, Long teamId) {
        Team t = teamService.requireManagedTeam(user, teamId);
        return forms.findByTeam(t).map(FormDto::of)
                .orElseGet(() -> new FormDto(null, t.getId(), t.getName(), "Join " + t.getName(), null,
                        false, false, null, new TreeSet<>(), List.of()));
    }

    @Transactional
    public FormDto save(User user, Long teamId, FormUpsert req) {
        Team t = teamService.requireManagedTeam(user, teamId);
        ApplicationForm f = forms.findByTeam(t).orElseGet(() -> {
            ApplicationForm nf = new ApplicationForm();
            nf.setTeam(t);
            return nf;
        });
        f.setTitle(req.title().trim());
        f.setIntro(TextUtil.trimToNull(req.intro()));
        f.setOpen(req.open());
        f.setDeadline(req.deadline());
        f.getEligibleGrades().clear();
        if (req.eligibleGrades() != null) f.getEligibleGrades().addAll(req.eligibleGrades());

        if (req.open() && req.questions().isEmpty())
            throw ApiException.badRequest("Add at least one question before opening applications");

        // match up the questions: edited ones keep their id, new ones get added, removed ones get deleted
        Map<Long, FormQuestion> existing = f.getQuestions().stream()
                .filter(q -> q.getId() != null)
                .collect(Collectors.toMap(FormQuestion::getId, Function.identity()));
        List<FormQuestion> next = new ArrayList<>();
        int pos = 0;
        for (QuestionDto dto : req.questions()) {
            if (dto.type() == QuestionType.MULTIPLE_CHOICE && optionList(dto.options()).size() < 2)
                throw ApiException.badRequest("Multiple-choice question \"" + dto.prompt() + "\" needs at least 2 options");
            FormQuestion q = dto.id() != null && existing.containsKey(dto.id()) ? existing.get(dto.id()) : new FormQuestion();
            q.setForm(f);
            q.setPosition(pos++);
            q.setPrompt(dto.prompt().trim());
            q.setHelpText(TextUtil.trimToNull(dto.helpText()));
            q.setType(dto.type());
            q.setRequired(dto.required());
            q.setWordLimit(dto.type() == QuestionType.MULTIPLE_CHOICE ? null : dto.wordLimit());
            q.setOptions(dto.type() == QuestionType.MULTIPLE_CHOICE ? String.join("\n", optionList(dto.options())) : null);
            next.add(q);
        }
        f.getQuestions().clear();
        f.getQuestions().addAll(next);
        return FormDto.of(forms.save(f));
    }

    static List<String> optionList(String options) {
        if (options == null) return List.of();
        return Arrays.stream(options.split("\\R")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }
}
