package org.readysetrecruit.web;

import org.readysetrecruit.config.ApiException;
import org.readysetrecruit.dto.TeamDtos.ClaimDto;
import org.readysetrecruit.model.ApplicationStatus;
import org.readysetrecruit.repo.ApplicationRepository;
import org.readysetrecruit.repo.TeamRepository;
import org.readysetrecruit.repo.UserRepository;
import org.readysetrecruit.scrape.ImportService;
import org.readysetrecruit.scrape.JobRunner;
import org.readysetrecruit.scrape.ScrapeService;
import org.readysetrecruit.service.TeamService;
import org.springframework.web.bind.annotation.*;

import java.time.Year;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final TeamService teams;
    private final JobRunner jobs;
    private final ScrapeService scrape;
    private final ImportService imports;
    private final TeamRepository teamRepo;
    private final UserRepository userRepo;
    private final ApplicationRepository appRepo;

    public AdminController(TeamService teams, JobRunner jobs, ScrapeService scrape, ImportService imports,
                           TeamRepository teamRepo, UserRepository userRepo, ApplicationRepository appRepo) {
        this.teams = teams;
        this.jobs = jobs;
        this.scrape = scrape;
        this.imports = imports;
        this.teamRepo = teamRepo;
        this.userRepo = userRepo;
        this.appRepo = appRepo;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("teams", teamRepo.count(), "claimedTeams", teamRepo.countByOwnerIsNotNull(),
                "users", userRepo.count(), "applications", appRepo.countByStatusNot(ApplicationStatus.DRAFT),
                "job", jobs.status());
    }

    @GetMapping("/claims")
    public List<ClaimDto> claims() { return teams.pendingClaims(); }

    @PostMapping("/claims/{id}/approve")
    public Map<String, String> approve(@PathVariable Long id) {
        teams.decideClaim(id, true);
        return Map.of("status", "approved");
    }

    @PostMapping("/claims/{id}/reject")
    public Map<String, String> reject(@PathVariable Long id) {
        teams.decideClaim(id, false);
        return Map.of("status", "rejected");
    }

    @GetMapping("/jobs")
    public JobRunner.JobStatus job() { return jobs.status(); }

    @PostMapping("/jobs/scrape")
    public JobRunner.JobStatus runScrape() { return started(jobs.start("scrape", scrape::runBatch)); }

    @PostMapping("/jobs/import-frc")
    public JobRunner.JobStatus importFrc(@RequestParam(required = false) Integer year) {
        int y = year != null ? year : Year.now().getValue();
        return started(jobs.start("import-frc", () -> imports.importFrc(y)));
    }

    @PostMapping("/jobs/import-ftc")
    public JobRunner.JobStatus importFtc(@RequestParam(required = false) Integer season) {
        int s = season != null ? season : Year.now().getValue();
        return started(jobs.start("import-ftc", () -> imports.importFtc(s)));
    }

    @PostMapping("/jobs/import-vex")
    public JobRunner.JobStatus importVex() { return started(jobs.start("import-vex", imports::importVex)); }

    // body: raw CSV text (Content-Type: text/csv or text/plain)
    @PostMapping(value = "/jobs/import-csv", consumes = {"text/csv", "text/plain"})
    public JobRunner.JobStatus importCsv(@RequestBody String csv) {
        if (csv.length() > 5_000_000) throw ApiException.badRequest("CSV too large (5 MB max)");
        return started(jobs.start("import-csv", () -> imports.importCsv(csv)));
    }

    private JobRunner.JobStatus started(boolean ok) {
        if (!ok) throw ApiException.conflict("Another job is already running");
        return jobs.status();
    }
}
