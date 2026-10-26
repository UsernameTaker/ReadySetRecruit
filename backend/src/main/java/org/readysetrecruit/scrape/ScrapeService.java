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

// Goes through team websites in batches.
// 1) teams with no website -> try to find one with a web search (only if BRAVE_API_KEY is set)
// 2) teams with a website -> check if it works, grab the title/description, a join link and an email
// this is how teams that never signed up still get a useful card
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

    // keeps doing batches until everything's checked or we run out of time.
    // the nightly github action calls this (and pings the server so render doesn't fall asleep)
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
        // check a few at once since they're all different websites anyway (each thread still waits a bit between sites)
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
                    // still mark it as checked or this one site gets retried forever
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

    // save what we found. dead sites get hidden right away, we check them again in 2 weeks
    public static void apply(Team t, WebsiteScraper.Result r) {
        t.setLastScrapedAt(Instant.now());
        if (r.blockedByRobots()) {
            t.setScrapeFailures(0);
            t.setWebsiteStatus(WebsiteStatus.BLOCKED_BY_ROBOTS);
            return; // still show the link, we just can't read the site
        }
        if (r.botBlocked()) {
            t.setScrapeFailures(0);
            t.setWebsiteStatus(WebsiteStatus.OK); // site works for people, it just blocks bots like us
            return;
        }
        if (!r.reachable()) {
            t.setScrapeFailures(t.getScrapeFailures() + 1);
            t.setWebsiteStatus(WebsiteStatus.UNREACHABLE);
            t.setJoinUrl(null);
            // if we found this site ourselves and it keeps failing it was probably the wrong site
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
