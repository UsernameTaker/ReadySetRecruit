package org.readysetrecruit.scrape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

// runs one background job at a time (scrape or import) and remembers how the last one went
@Component
public class JobRunner {
    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    public record JobStatus(String name, boolean running, Instant startedAt, Instant finishedAt, String summary) {}

    public interface Job { String run() throws Exception; }

    private final AtomicReference<JobStatus> status =
            new AtomicReference<>(new JobStatus(null, false, null, null, "No jobs have run yet"));

    // starts it on a new thread. returns false if something's already running
    public synchronized boolean start(String name, Job job) {
        if (status.get().running()) return false;
        Instant started = Instant.now();
        status.set(new JobStatus(name, true, started, null, "Running…"));
        Thread t = new Thread(() -> {
            String summary;
            try {
                summary = job.run();
            } catch (Exception e) {
                log.error("Job {} failed", name, e);
                summary = "Failed: " + e.getMessage();
            }
            log.info("Job {} finished: {}", name, summary);
            status.set(new JobStatus(name, false, started, Instant.now(), summary));
        }, "job-" + name);
        t.setDaemon(true);
        t.start();
        return true;
    }

    public JobStatus status() { return status.get(); }
}
