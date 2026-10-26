package org.readysetrecruit.web;

import jakarta.validation.Valid;
import org.readysetrecruit.config.CurrentUser;
import org.readysetrecruit.dto.ApplicationDtos.*;
import org.readysetrecruit.dto.FormDtos.*;
import org.readysetrecruit.dto.TeamDtos.*;
import org.readysetrecruit.service.ApplicationService;
import org.readysetrecruit.service.FormService;
import org.readysetrecruit.service.TeamService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/my/teams")
public class MyTeamsController {
    private final TeamService teams;
    private final FormService forms;
    private final ApplicationService apps;

    public MyTeamsController(TeamService teams, FormService forms, ApplicationService apps) {
        this.teams = teams;
        this.forms = forms;
        this.apps = apps;
    }

    @GetMapping
    public List<TeamDetail> mine() { return teams.myTeams(CurrentUser.get()); }

    @PostMapping
    public TeamDetail create(@Valid @RequestBody TeamUpsert req) { return teams.create(CurrentUser.get(), req); }

    @PutMapping("/{teamId}")
    public TeamDetail update(@PathVariable Long teamId, @Valid @RequestBody TeamUpsert req) {
        return teams.update(CurrentUser.get(), teamId, req);
    }

    @GetMapping("/{teamId}/form")
    public FormDto form(@PathVariable Long teamId) { return forms.formForEditing(CurrentUser.get(), teamId); }

    @PutMapping("/{teamId}/form")
    public FormDto saveForm(@PathVariable Long teamId, @Valid @RequestBody FormUpsert req) {
        return forms.save(CurrentUser.get(), teamId, req);
    }

    @GetMapping("/{teamId}/applications")
    public List<ApplicationDto> applications(@PathVariable Long teamId) {
        return apps.forTeam(CurrentUser.get(), teamId);
    }

    @PatchMapping("/{teamId}/applications/{appId}")
    public ApplicationDto review(@PathVariable Long teamId, @PathVariable Long appId,
                                 @Valid @RequestBody ApplicationReview req) {
        return apps.review(CurrentUser.get(), teamId, appId, req);
    }
}
