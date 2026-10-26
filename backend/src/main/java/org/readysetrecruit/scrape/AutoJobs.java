package org.readysetrecruit.scrape;

import org.readysetrecruit.repo.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Year;

/**
 * Keeps the directory filled without anyone clicking buttons on the admin page:
 *  - on startup, if no FTC/FRC teams have been imported yet, imports them and checks the first batch of websites
 *  - while the server is awake, checks another batch of team websites every few minutes
 * (On free hosts the server sleeps when idle; the GitHub Actions schedule wakes it up once a day.)
 */
@Component
@Order(2) // after DemoSeeder
public class AutoJobs implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AutoJobs.class);
    private static final int MIN_IMPORTED = 500;

    private final TeamRepository teams;
    private final JobRunner jobs;
    private final ImportService imports;
    private final ScrapeService scrape;
    private final boolean autoImport;
    private final boolean autoScrape;

    public AutoJobs(TeamRepository teams, JobRunner jobs, ImportService imports, ScrapeService scrape,
                    @Value("${app.import.auto}") boolean autoImport,
                    @Value("${app.scraper.auto}") boolean autoScrape) {
        this.teams = teams;
        this.jobs = jobs;
        this.imports = imports;
        this.scrape = scrape;
        this.autoImport = autoImport;
        this.autoScrape = autoScrape;
    }

    @Override
    public void run(ApplicationArguments args) {
        // There are thousands of each; a handful means an earlier import stopped partway.
        boolean needsFtc = teams.countByExternalKeyStartingWith("FTC-") < MIN_IMPORTED;
        boolean needsFrc = imports.frcEnabled() && teams.countByExternalKeyStartingWith("FRC-") < MIN_IMPORTED;
        if (!autoImport || (!needsFtc && !needsFrc)) return;
        log.info("No imported teams yet - importing the team lists in the background");
        jobs.start("import-all", () -> importAll(needsFtc, needsFrc));
    }

    /** Re-imports every list (picks up new teams and changed websites). Called weekly by the cron job. */
    public boolean refreshAll() {
        return jobs.start("import-all", () -> importAll(true, imports.frcEnabled()));
    }

    private String importAll(boolean ftc, boolean frc) throws Exception {
        StringBuilder summary = new StringBuilder();
        if (ftc) {
            try {
                summary.append(imports.importFtc(Year.now().getValue())).append(". ");
            } catch (Exception e) {
                log.warn("FTC import failed", e);
                summary.append("FTC import failed: ").append(e.getMessage()).append(". ");
            }
        }
        if (frc) {
            try {
                summary.append(imports.importFrc(Year.now().getValue())).append(". ");
            } catch (Exception e) {
                log.warn("FRC import failed", e);
                summary.append("FRC import failed: ").append(e.getMessage()).append(". ");
            }
        }
        return summary.append(scrape.runUntilDone(java.time.Duration.ofMinutes(40))).toString();
    }

    @Scheduled(initialDelayString = "${app.scraper.interval-minutes}", fixedDelayString = "${app.scraper.interval-minutes}",
            timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    void scrapeWhileAwake() {
        if (autoScrape) jobs.start("scrape", scrape::runBatch); // no-op if another job is running
    }
}
