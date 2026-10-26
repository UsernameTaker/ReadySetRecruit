package org.readysetrecruit.web;

import jakarta.validation.Valid;
import org.readysetrecruit.config.CurrentUser;
import org.readysetrecruit.dto.FormDtos.FormDto;
import org.readysetrecruit.dto.TeamDtos.*;
import org.readysetrecruit.model.TeamType;
import org.readysetrecruit.service.FormService;
import org.readysetrecruit.service.TeamService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PublicController {
    private final TeamService teams;
    private final FormService forms;

    public PublicController(TeamService teams, FormService forms) {
        this.teams = teams;
        this.forms = forms;
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    @GetMapping("/meta/programs")
    public List<String> programs() { return TeamService.PROGRAMS; }

    @GetMapping("/teams/search")
    public List<TeamSummary> search(@RequestParam(required = false) String zip,
                                    @RequestParam(required = false) Integer radius,
                                    @RequestParam(required = false) Integer grade,
                                    @RequestParam(required = false) String program,
                                    @RequestParam(required = false) TeamType type,
                                    @RequestParam(required = false) Boolean recruiting,
                                    @RequestParam(required = false) Boolean openApplications,
                                    @RequestParam(required = false) String state,
                                    @RequestParam(required = false) String q) {
        return teams.search(new TeamService.SearchParams(zip, radius, grade, program, type, recruiting,
                openApplications, state, q));
    }

    @GetMapping("/teams/{id}")
    public TeamDetail team(@PathVariable Long id) { return teams.detail(id); }

    @GetMapping("/teams/{id}/form")
    public FormDto form(@PathVariable Long id) { return forms.publicForm(id); }

    // a team account asks to take over an imported listing
    @PostMapping("/teams/{id}/claim")
    public Map<String, String> claim(@PathVariable Long id, @Valid @RequestBody ClaimCreate req) {
        teams.requestClaim(CurrentUser.get(), id, req.message());
        return Map.of("status", "pending");
    }
}
