package org.readysetrecruit.scrape;

import org.readysetrecruit.model.Team;
import org.readysetrecruit.model.WebsiteStatus;
import org.readysetrecruit.repo.TeamRepository;
import org.readysetrecruit.service.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The website pipeline, run in small batches (so it fits a free-tier server):
 *  1. For unclaimed teams with no website: optionally search the web for one.
 *  2. For teams with a website: check it's up, grab title/description, find a join/apply link and contact email.
 * Teams that never set up a ReadySetRecruit profile still get a useful card with a link to their own site.
 */
@Service
public class ScrapeService {
    private static final Logger log = LoggerFactory.getLogger(ScrapeService.class);

    private final TeamRepository teams;
    private final WebsiteScraper scraper;
    private final WebsiteSearch search;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final int recheckDays;
    private final long delayMs;
    private final int threads;

    public ScrapeService(TeamRepository teams, WebsiteScraper scraper, WebsiteSearch search, TransactionTemplate tx,
                         @Value("${app.scraper.batch-size}") int batchSize,
                         @Value("${app.scraper.recheck-days}") int recheckDays,
                         @Value("${app.scraper.delay-ms}") long delayMs,
                         @Value("${app.scraper.threads}") int threads) {
        this.teams = teams;
        this.scraper = scraper;
        this.search = search;
        this.tx = tx;
        this.batchSize = batchSize;
        this.recheckDays = recheckDays;
        this.delayMs = delayMs;
        this.threads = Math.max(1, threads);
    }

    private record Batch(int checked, String summary) {}

    public String runBatch() throws InterruptedException { return batch().summary(); }

    /**
     * Keeps checking batches until every website is up to date or the time budget runs out.
     * The nightly GitHub Action uses this (and keeps the free server awake while it runs).
     */
    public String runUntilDone(Duration budget) throws InterruptedException {
        Instant stop = Instant.now().plus(budget);
        int batches = 0, checked = 0;
        Batch last;
        do {
            last = batch();
            batches++;
            checked += last.checked();
        } while (last.checked() > 0 && Instant.now().isBefore(stop));
        String summary = "Checked " + checked + " websites in " + batches + " batches"
                + (last.checked() > 0 ? " (time ran out - continuing next run)" : " - all websites are up to date");
        log.info(summary);
        return summary;
    }

    private Batch batch() throws InterruptedException {
        Instant cutoff = Instant.now().minus(Duration.ofDays(recheckDays));
        int found = 0, searched = 0;

        if (search.enabled()) {
            List<Long> ids = tx.execute(s -> teams.findDueForWebsiteSearch(cutoff, PageRequest.of(0, batchSize / 2))
                    .stream().map(Team::getId).toList());
            for (Long id : ids) {
                searched++;
                Team snapshot = tx.execute(s -> teams.findById(id).map(t -> { t.getPrograms().size(); return t; }).orElse(null));
                if (snapshot == null) continue;
                var url = search.findWebsite(snapshot);
                boolean hit = url.isPresent();
                tx.executeWithoutResult(s -> teams.findById(id).ifPresent(t -> {
                    t.setLastSearchedAt(Instant.now());
                    if (hit && TextUtil.isBlank(t.getWebsiteUrl())) {
                        t.setWebsiteUrl(url.get());
                        t.setWebsiteAutoFound(true);
                        t.setWebsiteStatus(WebsiteStatus.UNKNOWN);
                    }
                }));
                if (hit) found++;
                Thread.sleep(delayMs);
            }
        }

        List<Long> due = tx.execute(s -> teams.findDueForScrape(cutoff, PageRequest.of(0, batchSize))
                .stream().map(Team::getId).toList());
        // Every team is a different website, so a few are checked at once; each thread still pauses between sites.
        AtomicInteger ok = new AtomicInteger(), failed = new AtomicInteger(), joinLinks = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (Long id : due) {
            pool.submit(() -> {
                try {
                    String url = tx.execute(s -> teams.findById(id).map(Team::getWebsiteUrl).orElse(null));
                    if (url == null) return null;
                    WebsiteScraper.Result r = scraper.scrape(url);
                    tx.executeWithoutResult(s -> teams.findById(id).ifPresent(t -> apply(t, r)));
                    (r.opensForPeople() ? ok : failed).incrementAndGet();
                    if (r.joinUrl() != null) joinLinks.incrementAndGet();
                } catch (Exception e) {
                    // Mark it checked anyway so one odd site can't block every future batch.
                    log.warn("Scraping team {} failed: {}", id, e.toString());
                    failed.incrementAndGet();
                    tx.executeWithoutResult(s -> teams.findById(id).ifPresent(t -> t.setLastScrapedAt(Instant.now())));
                }
                Thread.sleep(delayMs);
                return null;
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.MINUTES)) pool.shutdownNow();
        String summary = String.format("Searched %d teams (found %d sites). Checked %d websites: %d ok, %d failed, %d join links.",
                searched, found, due.size(), ok.get(), failed.get(), joinLinks.get());
        log.info(summary);
        return new Batch(due.size() + searched, summary);
    }

    /** Stores what a website check found. Unreachable sites are hidden right away and rechecked later. */
    public static void apply(Team t, WebsiteScraper.Result r) {
        t.setLastScrapedAt(Instant.now());
        if (r.blockedByRobots()) {
            t.setScrapeFailures(0);
            t.setWebsiteStatus(WebsiteStatus.BLOCKED_BY_ROBOTS);
            return; // we still show the link, we just don't read the site
        }
        if (r.botBlocked()) {
            t.setScrapeFailures(0);
            t.setWebsiteStatus(WebsiteStatus.OK); // works in a browser, it just won't talk to us
            return;
        }
        if (!r.reachable()) {
            t.setScrapeFailures(t.getScrapeFailures() + 1);
            t.setWebsiteStatus(WebsiteStatus.UNREACHABLE);
            t.setJoinUrl(null);
            // An auto-found site that keeps failing was probably wrong; drop it.
            if (t.isWebsiteAutoFound() && t.getScrapeFailures() >= 3) {
                t.setWebsiteUrl(null);
                t.setWebsiteAutoFound(false);
            }
            return;
        }
        t.setScrapeFailures(0);
        t.setWebsiteStatus(WebsiteStatus.OK);
        t.setWebsiteTitle(r.title());
        t.setWebsiteDescription(r.description());
        t.setJoinUrl(r.joinUrl());
        if (TextUtil.isBlank(t.getContactEmail()) && !t.isClaimed()) t.setContactEmail(r.email());
    }
}
