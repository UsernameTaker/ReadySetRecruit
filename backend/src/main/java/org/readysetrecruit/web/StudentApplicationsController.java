package org.readysetrecruit.web;

import jakarta.validation.Valid;
import org.readysetrecruit.config.ApiException;
import org.readysetrecruit.config.CurrentUser;
import org.readysetrecruit.dto.ApplicationDtos.*;
import org.readysetrecruit.service.ApplicationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/applications")
public class StudentApplicationsController {
    private final ApplicationService apps;

    public StudentApplicationsController(ApplicationService apps) { this.apps = apps; }

    @GetMapping
    public List<ApplicationDto> mine() { return apps.mine(CurrentUser.get()); }

    @GetMapping("/team/{teamId}")
    public ApplicationDto forTeam(@PathVariable Long teamId) {
        return apps.mineForTeam(CurrentUser.get(), teamId).orElseThrow(() -> ApiException.notFound("Application"));
    }

    // send {"submit": false} to save a draft, true to actually submit
    @PutMapping("/team/{teamId}")
    public ApplicationDto save(@PathVariable Long teamId, @Valid @RequestBody ApplicationSave req) {
        return apps.save(CurrentUser.get(), teamId, req);
    }

    @PostMapping("/{id}/withdraw")
    public ApplicationDto withdraw(@PathVariable Long id) { return apps.withdraw(CurrentUser.get(), id); }
}
