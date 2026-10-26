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

// Stuff that runs by itself so nobody has to press buttons on the admin page:
// - when the server starts and the team lists are empty, import them
// - every few minutes while the server is awake, check more websites
// (render free tier sleeps when nobody's using it, the github action wakes it up every night)
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
        // there should be thousands of each, if there's only a few the last import probably crashed halfway
        boolean needsFtc = teams.countByExternalKeyStartingWith("FTC-") < MIN_IMPORTED;
        boolean needsFrc = imports.frcEnabled() && teams.countByExternalKeyStartingWith("FRC-") < MIN_IMPORTED;
        boolean needsVex = imports.vexEnabled() && teams.countByExternalKeyStartingWith("VEX-") < MIN_IMPORTED;
        if (!autoImport || (!needsFtc && !needsFrc && !needsVex)) return;
        log.info("No imported teams yet - importing the team lists in the background");
        jobs.start("import-all", () -> importAll(needsFtc, needsFrc, needsVex));
    }

    // re-import everything (new teams, changed websites). the github action does this on sundays
    public boolean refreshAll() {
        return jobs.start("import-all", () -> importAll(true, imports.frcEnabled(), imports.vexEnabled()));
    }

    private String importAll(boolean ftc, boolean frc, boolean vex) throws Exception {
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
        if (vex) {
            try {
                summary.append(imports.importVex()).append(". ");
            } catch (Exception e) {
                log.warn("VEX import failed", e);
                summary.append("VEX import failed: ").append(e.getMessage()).append(". ");
            }
        }
        return summary.append(scrape.runUntilDone(java.time.Duration.ofMinutes(40))).toString();
    }

    @Scheduled(initialDelayString = "${app.scraper.interval-minutes}", fixedDelayString = "${app.scraper.interval-minutes}",
            timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    void scrapeWhileAwake() {
        if (autoScrape) jobs.start("scrape", scrape::runBatch); // does nothing if another job is already going
    }
}
