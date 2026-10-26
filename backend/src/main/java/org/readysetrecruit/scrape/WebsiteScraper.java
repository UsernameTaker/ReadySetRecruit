package org.readysetrecruit.scrape;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Visits one team website (politely: robots.txt, identifying user agent, size/time limits)
 * and pulls out the title, a description, a "join/apply/recruit" link and a contact email.
 */
@Component
public class WebsiteScraper {
    private static final Pattern JOIN_WORDS = Pattern.compile(
            "\\b(join|joining|recruit\\w*|apply|application|tryouts?|membership|sign[ -]?up|interest form|get involved|prospective)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Account pages of Google Sites, Wix, Squarespace etc. - they say "sign up" but aren't the team's. */
    private static final Pattern LOGIN_LINK = Pattern.compile(
            "accounts\\.google\\.com|/(login|signin|sign-in|account|accounts)(/|\\?|$)|wix\\.com/signup|squarespace\\.com/login",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final String userAgent;

    public WebsiteScraper(@Value("${app.scraper.user-agent}") String userAgent) {
        this.userAgent = userAgent;
    }

    /** Pages served on expired, parked or never-published domains. */
    private static final Pattern PARKED = Pattern.compile(
            "domain (name )?(is|may be) for sale|buy this domain|this domain (has expired|is parked|is not connected)"
                    + "|domain has expired|parked (free|domain)|hugedomains|domain isn.t connected to a website"
                    + "|website (has )?expired|site (is )?(not|no longer) (available|found|published)|account suspended",
            Pattern.CASE_INSENSITIVE);

    /**
     * reachable: the page opens. botBlocked: the server refused us (403/429...) but the site exists, so people can
     * still open it - we keep the link but can't read anything. blockedByRobots: robots.txt asks us not to read it.
     */
    public record Result(boolean reachable, boolean botBlocked, boolean blockedByRobots, String finalUrl, String title,
                         String description, String joinUrl, String email) {
        static Result unreachable() { return new Result(false, false, false, null, null, null, null, null); }
        static Result refused() { return new Result(false, true, false, null, null, null, null, null); }
        static Result blocked() { return new Result(false, false, true, null, null, null, null, null); }

        /** Whether a person clicking the link would get a working site. */
        public boolean opensForPeople() { return reachable || botBlocked || blockedByRobots; }
    }

    public Result scrape(String url) {
        URI uri;
        try {
            uri = URI.create(url);
            if (uri.getHost() == null) return Result.unreachable();
        } catch (IllegalArgumentException e) {
            return Result.unreachable();
        }
        if (!robotsFor(uri).allows(uri.getRawPath())) return Result.blocked();

        try {
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(12_000)
                    .maxBodySize(2_000_000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .execute();
            int code = res.statusCode();
            if (code == 401 || code == 403 || code == 429 || code == 503 && res.hasHeader("cf-ray")) return Result.refused();
            if (code >= 400) return Result.unreachable();
            String ct = res.contentType();
            if (ct != null && !ct.toLowerCase(Locale.ROOT).contains("html")) return Result.unreachable();
            Document doc = res.parse();
            if (isParked(doc)) return Result.unreachable();
            return new Result(true, false, false, res.url().toString(), clean(doc.title(), 200),
                    clean(description(doc), 900), findJoinLink(doc).orElse(null), findEmail(doc).orElse(null));
        } catch (Exception e) {
            return Result.unreachable();
        }
    }

    static boolean isParked(Document doc) {
        String text = doc.title() + " " + doc.body().text();
        if (text.length() > 20_000) text = text.substring(0, 20_000);
        return PARKED.matcher(text).find();
    }

    private RobotsTxt robotsFor(URI uri) {
        try {
            String robotsUrl = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "/robots.txt";
            Connection.Response r = Jsoup.connect(robotsUrl).userAgent(userAgent).timeout(6_000)
                    .ignoreContentType(true).ignoreHttpErrors(true).maxBodySize(500_000).execute();
            if (r.statusCode() >= 400) return RobotsTxt.allowAll();
            return RobotsTxt.parse(r.body(), userAgent);
        } catch (Exception e) {
            return RobotsTxt.allowAll();
        }
    }

    private static String description(Document doc) {
        for (String sel : new String[]{"meta[name=description]", "meta[property=og:description]", "meta[name=twitter:description]"}) {
            Element m = doc.selectFirst(sel);
            if (m != null && !m.attr("content").isBlank()) return m.attr("content");
        }
        // Fall back to the first reasonably long paragraph.
        for (Element p : doc.select("main p, article p, p")) {
            String t = p.text();
            if (t.length() >= 80) return t;
        }
        return null;
    }

    /** Prefers same-site links and form links (Google Forms, Jotform, etc.). */
    static Optional<String> findJoinLink(Document doc) {
        String bestUrl = null;
        int bestScore = 0;
        String host = hostOf(doc.location());
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            if (href.isEmpty() || href.startsWith("mailto:") || href.startsWith("javascript:") || href.length() > 255) continue;
            if (LOGIN_LINK.matcher(href).find()) continue; // "Sign in" / "Sign up" links of the site builder itself
            String text = a.text() + " " + a.attr("title") + " " + a.attr("aria-label");
            int score = 0;
            if (JOIN_WORDS.matcher(text).find()) score += 3;
            if (JOIN_WORDS.matcher(href.replace('-', ' ').replace('_', ' ').replace('/', ' ')).find()) score += 2;
            if (score == 0) continue;
            String h = hostOf(href);
            if (h != null && h.equals(host)) score += 1;
            if (href.contains("forms.gle") || href.contains("docs.google.com/forms") || href.contains("jotform")
                    || href.contains("forms.office.com")) score += 2;
            if (h != null && (h.contains("facebook.") || h.contains("instagram.") || h.contains("twitter.") || h.contains("x.com")))
                score -= 3;
            if (score > bestScore) {
                bestScore = score;
                bestUrl = href;
            }
        }
        return bestScore >= 3 ? Optional.of(bestUrl) : Optional.empty();
    }

    static Optional<String> findEmail(Document doc) {
        for (Element a : doc.select("a[href^=mailto:]")) {
            String addr = a.attr("href").substring(7).split("\\?")[0].trim();
            if (EMAIL.matcher(addr).matches()) return Optional.of(addr);
        }
        return Optional.empty();
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
