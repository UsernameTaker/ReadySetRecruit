package org.readysetrecruit.service;

import org.readysetrecruit.config.ApiException;
import org.readysetrecruit.dto.ApplicationDtos.*;
import org.readysetrecruit.model.*;
import org.readysetrecruit.repo.ApplicationFormRepository;
import org.readysetrecruit.repo.ApplicationRepository;
import org.readysetrecruit.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ApplicationService {
    private static final Set<ApplicationStatus> EDITABLE = EnumSet.of(ApplicationStatus.DRAFT, ApplicationStatus.WITHDRAWN);
    private static final Set<ApplicationStatus> TEAM_SETTABLE = EnumSet.of(ApplicationStatus.SUBMITTED,
            ApplicationStatus.UNDER_REVIEW, ApplicationStatus.ACCEPTED, ApplicationStatus.WAITLISTED, ApplicationStatus.DECLINED);

    private final ApplicationRepository apps;
    private final ApplicationFormRepository forms;
    private final UserRepository users;
    private final TeamService teamService;

    public ApplicationService(ApplicationRepository apps, ApplicationFormRepository forms, UserRepository users,
                              TeamService teamService) {
        this.apps = apps;
        this.forms = forms;
        this.users = users;
        this.teamService = teamService;
    }

    // ---------------- Students ----------------

    @Transactional(readOnly = true)
    public List<ApplicationDto> mine(User student) {
        return apps.findByStudentOrderByUpdatedAtDesc(users.getReferenceById(student.getId())).stream()
                .map(ApplicationDto::forStudent).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ApplicationDto> mineForTeam(User student, Long teamId) {
        return apps.findByTeamIdAndStudent(teamId, users.getReferenceById(student.getId())).map(ApplicationDto::forStudent);
    }

    // save a draft, or submit when req.submit() is true
    @Transactional
    public ApplicationDto save(User student, Long teamId, ApplicationSave req) {
        ApplicationForm form = forms.findByTeamId(teamId)
                .orElseThrow(() -> ApiException.badRequest("This team doesn't take applications on ReadySetRecruit"));
        User me = users.findById(student.getId()).orElseThrow(() -> ApiException.notFound("User"));

        Application app = apps.findByTeamIdAndStudent(teamId, me).orElseGet(() -> {
            Application a = new Application();
            a.setTeam(form.getTeam());
            a.setStudent(me);
            return a;
        });
        if (!EDITABLE.contains(app.getStatus()))
            throw ApiException.conflict("You've already submitted this application");
        if (!form.isAcceptingApplications())
            throw ApiException.badRequest("This team isn't accepting applications right now");

        Map<Long, FormQuestion> questions = form.getQuestions().stream()
                .collect(Collectors.toMap(FormQuestion::getId, Function.identity()));
        Map<Long, String> responses = new HashMap<>();
        for (AnswerInput in : req.answers()) {
            if (questions.containsKey(in.questionId())) responses.put(in.questionId(), in.response() == null ? "" : in.response());
        }

        List<String> problems = new ArrayList<>();
        List<Answer> answers = new ArrayList<>();
        for (FormQuestion q : form.getQuestions()) {
            String r = responses.getOrDefault(q.getId(), "").strip();
            if (q.getWordLimit() != null && TextUtil.wordCount(r) > q.getWordLimit())
                problems.add("\"" + q.getPrompt() + "\" is over the " + q.getWordLimit() + "-word limit");
            if (req.submit()) {
                if (q.isRequired() && r.isEmpty()) problems.add("\"" + q.getPrompt() + "\" is required");
                if (q.getType() == QuestionType.MULTIPLE_CHOICE && !r.isEmpty()
                        && !FormService.optionList(q.getOptions()).contains(r))
                    problems.add("Pick one of the options for \"" + q.getPrompt() + "\"");
            }
            answers.add(new Answer(q.getId(), q.getPrompt(), r));
        }

        app.setGrade(req.grade());
        app.setParentEmail(TextUtil.trimToNull(req.parentEmail()));
        if (req.submit()) {
            if (req.grade() == null) problems.add("Please choose your grade");
            else if (!form.acceptsGrade(req.grade())) problems.add("This team isn't accepting students in your grade");
            if (app.getParentEmail() == null) problems.add("A parent/guardian email is required");
        }
        if (!problems.isEmpty()) throw ApiException.badRequest(String.join(". ", problems));

        app.setAnswers(answers);
        if (req.submit()) {
            app.setStatus(ApplicationStatus.SUBMITTED);
            app.setSubmittedAt(Instant.now());
            if (me.getGrade() == null) me.setGrade(req.grade());
            if (me.getParentEmail() == null) me.setParentEmail(app.getParentEmail());
        } else {
            app.setStatus(ApplicationStatus.DRAFT);
        }
        return ApplicationDto.forStudent(apps.save(app));
    }

    @Transactional
    public ApplicationDto withdraw(User student, Long appId) {
        Application a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Application"));
        if (!a.getStudent().getId().equals(student.getId())) throw ApiException.notFound("Application");
        if (a.getStatus() == ApplicationStatus.ACCEPTED || a.getStatus() == ApplicationStatus.DECLINED)
            throw ApiException.conflict("A decision has already been made on this application");
        a.setStatus(ApplicationStatus.WITHDRAWN);
        return ApplicationDto.forStudent(a);
    }

    // ---------------- Teams ----------------

    @Transactional(readOnly = true)
    public List<ApplicationDto> forTeam(User user, Long teamId) {
        Team t = teamService.requireManagedTeam(user, teamId);
        return apps.findByTeamAndStatusNotInOrderBySubmittedAtDesc(t, EnumSet.of(ApplicationStatus.DRAFT)).stream()
                .map(ApplicationDto::forTeam).toList();
    }

    @Transactional
    public ApplicationDto review(User user, Long teamId, Long appId, ApplicationReview req) {
        Team t = teamService.requireManagedTeam(user, teamId);
        Application a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Application"));
        if (!a.getTeam().getId().equals(t.getId()) || a.getStatus() == ApplicationStatus.DRAFT)
            throw ApiException.notFound("Application");
        if (a.getStatus() == ApplicationStatus.WITHDRAWN)
            throw ApiException.conflict("The student withdrew this application");
        if (!TEAM_SETTABLE.contains(req.status())) throw ApiException.badRequest("Invalid status");
        a.setStatus(req.status());
        a.setTeamNotes(TextUtil.trimToNull(req.teamNotes()));
        return ApplicationDto.forTeam(a);
    }
}
