package org.readysetrecruit.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.readysetrecruit.model.*;
import org.readysetrecruit.repo.TeamRepository;
import org.readysetrecruit.service.TextUtil;
import org.readysetrecruit.service.ZipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Seeds the directory with teams that haven't signed up yet, from public team lists:
 *  - FRC teams from The Blue Alliance API (free key: thebluealliance.com/account)
 *  - FTC teams from FTCScout (no key needed; used automatically when the FIRST FTC Events API key isn't set)
 *  - FTC teams from the FIRST FTC Events API (free key: ftc-events.firstinspires.org/services/API)
 *  - Any CSV you put together yourself (VEX, FLL, school clubs, ...)
 * Imports upsert by an external key, and never overwrite teams that have been claimed by their owners.
 */
@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final int CHUNK = 200;
    /** Organization names that are clearly a school (vs. 4-H, scouts, "Family/Community", ...). */
    private static final Pattern SCHOOL_WORDS = Pattern.compile(
            "\\b(school|sch|high|hs|middle|elementary|academy|prep\\w*|collegiate|charter|isd|usd|district|lyceum)\\b",
            Pattern.CASE_INSENSITIVE);

    private final TeamRepository teams;
    private final ZipService zips;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final String tbaKey;
    private final String ftcUser;
    private final String ftcToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ImportService(TeamRepository teams, ZipService zips, ObjectMapper json, TransactionTemplate tx,
                         @Value("${app.import.tba-api-key}") String tbaKey,
                         @Value("${app.import.ftc-username}") String ftcUser,
                         @Value("${app.import.ftc-token}") String ftcToken) {
        this.teams = teams;
        this.zips = zips;
        this.json = json;
        this.tx = tx;
        this.tbaKey = tbaKey;
        this.ftcUser = ftcUser;
        this.ftcToken = ftcToken;
    }

    /** A team row coming from any source. */
    public record ImportRow(String externalKey, String name, String teamNumber, TeamType type, Set<String> programs,
                            String schoolName, String city, String state, String zip, String website,
                            Integer minGrade, Integer maxGrade) {}

    // ---------------- The Blue Alliance (FRC) ----------------

    public String importFrc(int year) throws Exception {
        if (TextUtil.isBlank(tbaKey)) throw new IllegalStateException("Set TBA_API_KEY to import FRC teams");
        List<ImportRow> rows = new ArrayList<>();
        for (int page = 0; page < 40; page++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://www.thebluealliance.com/api/v3/teams/" + year + "/" + page))
                    .header("X-TBA-Auth-Key", tbaKey).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("TBA returned " + res.statusCode());
            JsonNode arr = json.readTree(res.body());
            if (!arr.isArray() || arr.isEmpty()) break;
            for (JsonNode t : arr) {
                if (!"USA".equalsIgnoreCase(t.path("country").asText())) continue;
                String number = t.path("team_number").asText();
                String school = text(t, "school_name");
                rows.add(new ImportRow("FRC-" + number, firstNonBlank(text(t, "nickname"), "FRC Team " + number), number,
                        guessType(school), Set.of("FRC"), school,
                        text(t, "city"), States.toCode(text(t, "state_prov")), text(t, "postal_code"),
                        text(t, "website"), 9, 12));
            }
        }
        upsertAll(rows);
        return "Imported/updated " + rows.size() + " FRC teams for " + year;
    }

    // ---------------- FIRST FTC Events API ----------------

    public boolean frcEnabled() { return !TextUtil.isBlank(tbaKey); }

    /** Uses the official FIRST API when its key is set, otherwise FTCScout. */
    public String importFtc(int season) throws Exception {
        if (TextUtil.isBlank(ftcUser) || TextUtil.isBlank(ftcToken)) return importFtcScout();
        String auth = Base64.getEncoder().encodeToString((ftcUser + ":" + ftcToken).getBytes(StandardCharsets.UTF_8));
        List<ImportRow> rows = new ArrayList<>();
        int pageTotal = 1;
        for (int page = 1; page <= pageTotal && page <= 200; page++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://ftc-api.firstinspires.org/v2.0/" + season + "/teams?page=" + page))
                    .header("Authorization", "Basic " + auth).header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("FTC API returned " + res.statusCode());
            JsonNode root = json.readTree(res.body());
            pageTotal = root.path("pageTotal").asInt(1);
            for (JsonNode t : root.path("teams")) {
                if (!"USA".equalsIgnoreCase(t.path("country").asText())) continue;
                String number = t.path("teamNumber").asText();
                String school = text(t, "schoolName");
                rows.add(new ImportRow("FTC-" + number, firstNonBlank(text(t, "nameShort"), "FTC Team " + number), number,
                        guessType(school), Set.of("FTC"), school,
                        text(t, "city"), States.toCode(text(t, "stateProv")), null, text(t, "website"), 7, 12));
            }
        }
        upsertAll(rows);
        return "Imported/updated " + rows.size() + " FTC teams for season " + season;
    }

    // ---------------- FTCScout (FTC, no key) ----------------

    /**
     * All US FTC teams that played at an event this season or last season (ftcscout.org lists every team
     * ever registered, so this skips teams that have folded).
     */
    public String importFtcScout() throws Exception {
        LocalDate today = LocalDate.now();
        int season = today.getMonthValue() >= 9 ? today.getYear() : today.getYear() - 1; // FTC seasons start in September
        Set<Integer> active = new HashSet<>();
        for (int s : new int[]{season - 1, season}) {
            String query = "{\"query\":\"{ eventsSearch(season: " + s + ", limit: 100000) { teams { teamNumber } } }\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ftcscout.org/graphql"))
                    .header("Content-Type", "application/json").timeout(Duration.ofMinutes(3))
                    .POST(HttpRequest.BodyPublishers.ofString(query)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("FTCScout returned " + res.statusCode());
            for (JsonNode event : json.readTree(res.body()).path("data").path("eventsSearch"))
                for (JsonNode t : event.path("teams")) active.add(t.path("teamNumber").asInt());
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ftcscout.org/rest/v1/teams/search?limit=100000"))
                .header("Accept", "application/json").timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("FTCScout returned " + res.statusCode());
        List<ImportRow> rows = new ArrayList<>();
        for (JsonNode t : json.readTree(res.body())) {
            if (!"USA".equalsIgnoreCase(t.path("country").asText()) || !active.contains(t.path("number").asInt())) continue;
            String number = t.path("number").asText();
            String org = text(t, "schoolName");
            rows.add(new ImportRow("FTC-" + number, firstNonBlank(text(t, "name"), "FTC Team " + number), number,
                    guessType(org), Set.of("FTC"), org, text(t, "city"), States.toCode(text(t, "state")), null,
                    text(t, "website"), 7, 12));
        }
        upsertAll(rows);
        return "Imported/updated " + rows.size() + " active FTC teams from FTCScout";
    }

    // ---------------- CSV ----------------

    /**
     * Columns (header row required, order doesn't matter):
     * name, teamNumber, type (COMMUNITY|SCHOOL), programs (separated by ;), schoolName, city, state, zip, website,
     * minGrade, maxGrade
     */
    public String importCsv(String csv) {
        List<List<String>> rows = Csv.parse(csv);
        if (rows.size() < 2) return "No rows found";
        Map<String, Integer> col = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) col.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        if (!col.containsKey("name")) throw new IllegalArgumentException("CSV needs a 'name' column");

        List<ImportRow> out = new ArrayList<>();
        int skipped = 0;
        for (List<String> r : rows.subList(1, rows.size())) {
            String name = cell(r, col, "name");
            if (name == null) { skipped++; continue; }
            Set<String> programs = new LinkedHashSet<>();
            String progs = cell(r, col, "programs");
            if (progs != null) Arrays.stream(progs.split(";")).map(String::trim).filter(s -> !s.isEmpty()).forEach(programs::add);
            String typeStr = cell(r, col, "type");
            TeamType type = "SCHOOL".equalsIgnoreCase(typeStr) ? TeamType.SCHOOL : TeamType.COMMUNITY;
            String number = cell(r, col, "teamnumber");
            String key = "CSV-" + (number != null ? (programs.isEmpty() ? "" : programs.iterator().next() + "-") + number
                    : (name + "-" + Objects.toString(cell(r, col, "zip"), "")).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
            out.add(new ImportRow(key, name, number, type, programs, cell(r, col, "schoolname"), cell(r, col, "city"),
                    States.toCode(cell(r, col, "state")), cell(r, col, "zip"), cell(r, col, "website"),
                    intOrNull(cell(r, col, "mingrade")), intOrNull(cell(r, col, "maxgrade"))));
        }
        upsertAll(out);
        return "Imported/updated " + out.size() + " teams from CSV" + (skipped > 0 ? " (" + skipped + " rows skipped)" : "");
    }

    // ---------------- shared ----------------

    /** Saves rows in chunks so thousands of teams don't need thousands of transactions. */
    void upsertAll(List<ImportRow> rows) {
        for (int i = 0; i < rows.size(); i += CHUNK) {
            List<ImportRow> chunk = rows.subList(i, Math.min(rows.size(), i + CHUNK));
            // Geocode outside the DB transaction (it may call the network for places not in the bundled data).
            Map<ImportRow, ZipLocation> locs = new HashMap<>();
            for (ImportRow row : chunk) {
                ZipLocation loc = null;
                if (!TextUtil.isBlank(row.zip())) loc = zips.lookup(row.zip()).orElse(null);
                if (loc == null && row.city() != null && row.state() != null) loc = zips.lookupCity(row.state(), row.city()).orElse(null);
                if (loc != null) locs.put(row, loc);
            }
            tx.executeWithoutResult(s -> chunk.forEach(row -> save(row, locs.get(row))));
            log.info("Import: saved {}/{} teams", Math.min(rows.size(), i + CHUNK), rows.size());
        }
    }

    private void save(ImportRow row, ZipLocation l) {
        Team t = teams.findByExternalKey(row.externalKey()).orElseGet(Team::new);
        if (t.isClaimed()) return; // the team manages its own profile now
        t.setExternalKey(row.externalKey());
        t.setSource(TeamSource.IMPORTED);
        t.setName(fit(row.name()));
        t.setTeamNumber(row.teamNumber());
        t.setType(row.type());
        t.getPrograms().addAll(row.programs());
        t.setSchoolName(fit(row.schoolName()));
        t.setCity(fit(row.city()));
        t.setState(row.state());
        if (t.getMinGrade() == null) t.setMinGrade(row.minGrade());
        if (t.getMaxGrade() == null) t.setMaxGrade(row.maxGrade());
        if (l != null) {
            t.setZip(!TextUtil.isBlank(row.zip()) ? row.zip().substring(0, Math.min(5, row.zip().length())) : l.getZip());
            t.setLatitude(l.getLatitude());
            t.setLongitude(l.getLongitude());
        }
        String site = TextUtil.normalizeUrl(fit(row.website()));
        if (isDirectorySite(site)) site = null; // e.g. firstinspires.org - not the team's own site
        if (isDirectorySite(t.getWebsiteUrl())) t.setWebsiteUrl(null);
        if (site != null && !site.equals(t.getWebsiteUrl())) {
            t.setWebsiteUrl(site);
            t.setWebsiteAutoFound(false);
            t.setLastScrapedAt(null);
        }
        teams.save(t);
    }

    /** Imported text can be messy (TBA lists every school of a multi-school team, HTML-escaped); keep it column-sized. */
    static String fit(String s) {
        if (s == null) return null;
        String t = s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim();
        return t.length() <= 255 ? t : t.substring(0, 254).trim() + "…";
    }

    private static final Set<String> DIRECTORY_HOSTS = Set.of("firstinspires.org", "thebluealliance.com", "ftcscout.org",
            "robotevents.com", "vexrobotics.com", "statbotics.io", "frc-events.firstinspires.org", "ftc-events.firstinspires.org");

    static boolean isDirectorySite(String url) {
        if (url == null) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return true;
            String h = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            return DIRECTORY_HOSTS.stream().anyMatch(d -> h.equals(d) || h.endsWith("." + d));
        } catch (Exception e) {
            return true; // not a usable URL
        }
    }

    static TeamType guessType(String organization) {
        return organization != null && SCHOOL_WORDS.matcher(organization).find() ? TeamType.SCHOOL : TeamType.COMMUNITY;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : TextUtil.trimToNull(v.asText());
    }

    private static String cell(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? null : TextUtil.trimToNull(row.get(i));
    }

    private static Integer intOrNull(String s) {
        if (s == null) return null;
        if (s.equalsIgnoreCase("K")) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static String firstNonBlank(String a, String b) { return TextUtil.isBlank(a) ? b : a; }

    /** Tiny RFC-4180-ish CSV parser (quotes, escaped quotes, commas/newlines inside quotes). */
    static final class Csv {
        static List<List<String>> parse(String s) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else if (c == '"') quoted = false;
                    else cur.append(c);
                } else if (c == '"') quoted = true;
                else if (c == ',') { row.add(cur.toString()); cur.setLength(0); }
                else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                    row.add(cur.toString()); cur.setLength(0);
                    if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                    row = new ArrayList<>();
                } else cur.append(c);
            }
            if (cur.length() > 0 || !row.isEmpty()) { row.add(cur.toString()); rows.add(row); }
            return rows;
        }
    }
}
