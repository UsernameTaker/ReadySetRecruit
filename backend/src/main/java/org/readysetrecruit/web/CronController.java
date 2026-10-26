package org.readysetrecruit.web;

import org.readysetrecruit.config.ApiException;
import org.readysetrecruit.scrape.AutoJobs;
import org.readysetrecruit.scrape.JobRunner;
import org.readysetrecruit.scrape.ScrapeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Called by the GitHub Actions schedule (.github/workflows/scrape.yml) with the shared CRON_TOKEN.
 * Free hosts put the server to sleep, so an external schedule is more reliable than @Scheduled.
 */
@RestController
@RequestMapping("/api/cron")
public class CronController {
    private final String token;
    private final JobRunner jobs;
    private final ScrapeService scrape;
    private final AutoJobs auto;
    static final Duration JOB_BUDGET = Duration.ofMinutes(40);

    public CronController(@Value("${app.cron-token}") String token, JobRunner jobs, ScrapeService scrape, AutoJobs auto) {
        this.token = token;
        this.jobs = jobs;
        this.scrape = scrape;
        this.auto = auto;
    }

    @PostMapping("/scrape")
    public Map<String, Object> scrape(@RequestHeader(value = "X-Cron-Token", required = false) String given) {
        checkToken(given);
        boolean started = jobs.start("scrape", () -> scrape.runUntilDone(JOB_BUDGET));
        return Map.of("started", started, "job", jobs.status());
    }

    /** Lets the GitHub Action wait for the job (its requests also keep a free-tier server from sleeping). */
    @GetMapping("/status")
    public JobRunner.JobStatus status(@RequestHeader(value = "X-Cron-Token", required = false) String given) {
        checkToken(given);
        return jobs.status();
    }

    /** Re-imports the team lists (new teams, changed websites), then checks a batch of websites. */
    @PostMapping("/import")
    public Map<String, Object> importTeams(@RequestHeader(value = "X-Cron-Token", required = false) String given) {
        checkToken(given);
        boolean started = auto.refreshAll();
        return Map.of("started", started, "job", jobs.status());
    }

    private void checkToken(String given) {
        if (token == null || token.isBlank() || given == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Bad cron token");
    }
}
