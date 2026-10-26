package org.readysetrecruit.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.readysetrecruit.model.Team;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

// Optional, only runs if BRAVE_API_KEY is set.
// For teams with no website it searches the web and keeps a result only if it really looks like their site.
// these show as "found automatically" on the team page until the team confirms it
@Component
public class WebsiteSearch {
    private static final Logger log = LoggerFactory.getLogger(WebsiteSearch.class);
    // directories and social media, not the team's actual site
    private static final Set<String> SKIP_HOSTS = Set.of("thebluealliance.com", "firstinspires.org", "ftcscout.org",
            "frc-events.firstinspires.org", "ftc-events.firstinspires.org", "robotevents.com", "facebook.com",
            "instagram.com", "twitter.com", "x.com", "youtube.com", "linkedin.com", "wikipedia.org", "tiktok.com",
            "statbotics.io", "chiefdelphi.com", "reddit.com", "github.com", "greatschools.org", "niche.com");

    private final String apiKey;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WebsiteSearch(@Value("${app.search.brave-api-key}") String apiKey, ObjectMapper json) {
        this.apiKey = apiKey;
        this.json = json;
    }

    public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }

    public Optional<String> findWebsite(Team team) {
        if (!enabled()) return Optional.empty();
        String program = team.getPrograms().stream().findFirst().orElse("robotics");
        String q = "\"" + team.getName() + "\" " + program + " robotics team"
                + (team.getTeamNumber() != null ? " " + team.getTeamNumber() : "")
                + (team.getCity() != null ? " " + team.getCity() : "");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.search.brave.com/res/v1/web/search?count=10&q="
                            + URLEncoder.encode(q, StandardCharsets.UTF_8)))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", apiKey)
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Search API returned {}", res.statusCode());
                return Optional.empty();
            }
            JsonNode results = json.readTree(res.body()).path("web").path("results");
            for (JsonNode r : results) {
                String url = r.path("url").asText("");
                String text = (r.path("title").asText("") + " " + r.path("description").asText("") + " " + url).toLowerCase(Locale.ROOT);
                if (url.isEmpty() || skipped(url)) continue;
                if (looksLikeTeam(team, text)) return Optional.of(rootOf(url));
            }
        } catch (Exception e) {
            log.warn("Website search failed for team {}: {}", team.getId(), e.toString());
        }
        return Optional.empty();
    }

    // pretty strict on purpose. team number has to show up, or if there's no number most of the name has to
    static boolean looksLikeTeam(Team team, String text) {
        if (team.getTeamNumber() != null && !team.getTeamNumber().isBlank()) {
            return text.matches("(?s).*\\b" + java.util.regex.Pattern.quote(team.getTeamNumber().trim()) + "\\b.*")
                    && text.contains("robot");
        }
        String[] words = team.getName().toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        long significant = Arrays.stream(words).filter(w -> w.length() > 2).count();
        long hits = Arrays.stream(words).filter(w -> w.length() > 2 && text.contains(w)).count();
        return significant > 0 && hits * 10 >= significant * 8 && text.contains("robot");
    }

    private static boolean skipped(String url) {
        try {
            String host = URI.create(url).getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            return SKIP_HOSTS.stream().anyMatch(s -> host.equals(s) || host.endsWith("." + s));
        } catch (Exception e) {
            return true;
        }
    }

    private static String rootOf(String url) {
        URI u = URI.create(url);
        // for google sites etc keep the full path (sites.google.com/view/team123), otherwise just the domain
        String host = u.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("sites.google.com") || host.endsWith("wixsite.com") || host.endsWith("weebly.com")
                || host.endsWith("github.io")) return url;
        return u.getScheme() + "://" + u.getHost() + "/";
    }
}
