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

// Pulls in teams that haven't made an account yet so the search isn't empty.
// Sources: FTCScout (FTC, no key), The Blue Alliance (FRC, key), events.vex.com (VEX, key),
// the official FIRST FTC api if you have a key for it, or a csv you upload on the admin page.
// If a team already claimed their listing we leave it alone.
@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private static final int CHUNK = 200; // how many teams we save per db transaction

    // words that mean the org is a school and not like a 4-H club or a family team
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
    private final String robotEventsKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ImportService(TeamRepository teams, ZipService zips, ObjectMapper json, TransactionTemplate tx,
                         @Value("${app.import.tba-api-key}") String tbaKey,
                         @Value("${app.import.ftc-username}") String ftcUser,
                         @Value("${app.import.ftc-token}") String ftcToken,
                         @Value("${app.import.robotevents-api-key}") String robotEventsKey) {
        this.teams = teams;
        this.zips = zips;
        this.json = json;
        this.tx = tx;
        this.tbaKey = tbaKey;
        this.ftcUser = ftcUser;
        this.ftcToken = ftcToken;
        this.robotEventsKey = robotEventsKey;
    }

    // one team from whatever source, before we save it
    public record ImportRow(String externalKey, String name, String teamNumber, TeamType type, Set<String> programs,
                            String schoolName, String city, String state, String zip, String website,
                            Integer minGrade, Integer maxGrade) {}

    public boolean frcEnabled() {
        return !TextUtil.isBlank(tbaKey);
    }

    public boolean vexEnabled() {
        return !TextUtil.isBlank(robotEventsKey);
    }

    // ===== FRC (The Blue Alliance) =====

    public String importFrc(int year) throws Exception {
        if (TextUtil.isBlank(tbaKey)) {
            throw new IllegalStateException("Set TBA_API_KEY to import FRC teams");
        }
        List<ImportRow> rows = new ArrayList<>();
        // TBA gives 500 teams per page, there's ~20 pages right now. 40 is just a safety limit
        for (int page = 0; page < 40; page++) {
            String url = "https://www.thebluealliance.com/api/v3/teams/" + year + "/" + page;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("X-TBA-Auth-Key", tbaKey)
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("TBA returned " + res.statusCode());
            }

            JsonNode arr = json.readTree(res.body());
            if (!arr.isArray() || arr.isEmpty()) break; // no more pages

            for (JsonNode t : arr) {
                if (!"USA".equalsIgnoreCase(t.path("country").asText())) continue;
                String number = t.path("team_number").asText();
                String school = text(t, "school_name");
                String name = firstNonBlank(text(t, "nickname"), "FRC Team " + number);
                rows.add(new ImportRow("FRC-" + number, name, number, guessType(school), Set.of("FRC"), school,
                        text(t, "city"), States.toCode(text(t, "state_prov")), text(t, "postal_code"),
                        text(t, "website"), 9, 12));
            }
        }
        upsertAll(rows);
        return "Imported/updated " + rows.size() + " FRC teams for " + year;
    }

    // ===== FTC =====

    // if someone set up the official FIRST api key use that, otherwise FTCScout works fine
    public String importFtc(int season) throws Exception {
        if (TextUtil.isBlank(ftcUser) || TextUtil.isBlank(ftcToken)) {
            return importFtcScout();
        }

        String auth = Base64.getEncoder().encodeToString((ftcUser + ":" + ftcToken).getBytes(StandardCharsets.UTF_8));
        List<ImportRow> rows = new ArrayList<>();
        int pageTotal = 1;
        for (int page = 1; page <= pageTotal && page <= 200; page++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://ftc-api.firstinspires.org/v2.0/" + season + "/teams?page=" + page))
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
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

    // FTCScout has every team that ever existed (like 25k), a lot of them are dead.
    // So first we grab every team that showed up to an event this season or last season,
    // and only import those.
    public String importFtcScout() throws Exception {
        LocalDate today = LocalDate.now();
        int season = today.getYear();
        if (today.getMonthValue() < 9) {
            season = season - 1; // ftc season starts in september
        }

        Set<Integer> active = new HashSet<>();
        int[] seasons = {season - 1, season};
        for (int s : seasons) {
            // graphql query, last season's one takes like 45 sec so the timeout is long
            String query = "{\"query\":\"{ eventsSearch(season: " + s + ", limit: 100000) { teams { teamNumber } } }\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ftcscout.org/graphql"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(3))
                    .POST(HttpRequest.BodyPublishers.ofString(query)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("FTCScout returned " + res.statusCode());

            JsonNode events = json.readTree(res.body()).path("data").path("eventsSearch");
            for (JsonNode event : events) {
                for (JsonNode t : event.path("teams")) {
                    active.add(t.path("teamNumber").asInt());
                }
            }
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ftcscout.org/rest/v1/teams/search?limit=100000"))
                .header("Accept", "application/json").timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("FTCScout returned " + res.statusCode());

        List<ImportRow> rows = new ArrayList<>();
        for (JsonNode t : json.readTree(res.body())) {
            boolean inUsa = "USA".equalsIgnoreCase(t.path("country").asText());
            if (!inUsa) continue;
            if (!active.contains(t.path("number").asInt())) continue;

            String number = t.path("number").asText();
            String org = text(t, "schoolName"); // not always a school, sometimes it's "Girl Scouts" etc
            rows.add(new ImportRow("FTC-" + number, firstNonBlank(text(t, "name"), "FTC Team " + number), number,
                    guessType(org), Set.of("FTC"), org, text(t, "city"), States.toCode(text(t, "state")), null,
                    text(t, "website"), 7, 12));
        }
        upsertAll(rows);
        return "Imported/updated " + rows.size() + " active FTC teams from FTCScout";
    }

    // ===== VEX (events.vex.com, used to be robotevents.com) =====

    // program ids on their api. we skip VEX U (id 4) since that's college
    private static final Map<Integer, String> VEX_PROGRAMS = Map.of(1, "VEX V5", 41, "VEX IQ");

    public String importVex() throws Exception {
        if (!vexEnabled()) throw new IllegalStateException("Set ROBOTEVENTS_API_KEY to import VEX teams");

        List<ImportRow> rows = new ArrayList<>();
        for (int programId : VEX_PROGRAMS.keySet()) {
            int lastPage = 1;
            for (int page = 1; page <= lastPage && page <= 200; page++) {
                String url = "https://events.vex.com/api/v2/teams?registered=true&country%5B%5D=US&program%5B%5D="
                        + programId + "&per_page=250&page=" + page;
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + robotEventsKey.trim())
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(60)).GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

                int code = res.statusCode();
                if (code == 401 || code == 403) {
                    throw new IllegalStateException("RobotEvents rejected the API key (HTTP " + code + ")");
                }
                if (code != 200) throw new IllegalStateException("RobotEvents returned " + code);

                JsonNode root = json.readTree(res.body());
                lastPage = root.path("meta").path("last_page").asInt(1);
                rows.addAll(vexRows(root));

                Thread.sleep(300); // they rate limit, don't spam them
            }
        }
        upsertAll(rows);
        return "Imported/updated " + rows.size() + " VEX teams from RobotEvents";
    }

    // one page of /teams results -> rows (separate method so the test can call it)
    static List<ImportRow> vexRows(JsonNode page) {
        List<ImportRow> rows = new ArrayList<>();
        for (JsonNode t : page.path("data")) {
            String program = VEX_PROGRAMS.get(t.path("program").path("id").asInt());
            String number = text(t, "number");
            if (program == null || number == null) continue;

            JsonNode loc = t.path("location");
            if (!loc.isMissingNode()) {
                String country = loc.path("country").asText("United States");
                if (!country.equalsIgnoreCase("United States")) continue;
            }

            // vex only tells us the division, so guess a grade range from it
            int minGrade, maxGrade;
            String grade = t.path("grade").asText("");
            if (grade.equals("Elementary School")) {
                minGrade = 3;
                maxGrade = 5;
            } else if (grade.equals("Middle School")) {
                minGrade = 6;
                maxGrade = 8;
            } else if (grade.equals("High School")) {
                minGrade = 9;
                maxGrade = 12;
            } else {
                continue; // college or something weird
            }

            String org = text(t, "organization");
            String name = firstNonBlank(text(t, "team_name"), "VEX Team " + number);
            rows.add(new ImportRow("VEX-" + number, name, number, guessType(org), Set.of(program), org,
                    text(loc, "city"), States.toCode(text(loc, "region")), text(loc, "postcode"), null,
                    minGrade, maxGrade));
        }
        return rows;
    }

    // ===== CSV =====

    // needs a header row. columns can be in any order:
    // name, teamNumber, type (COMMUNITY or SCHOOL), programs (split with ;), schoolName, city, state, zip, website, minGrade, maxGrade
    public String importCsv(String csv) {
        List<List<String>> rows = Csv.parse(csv);
        if (rows.size() < 2) return "No rows found";

        Map<String, Integer> col = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        if (!col.containsKey("name")) throw new IllegalArgumentException("CSV needs a 'name' column");

        List<ImportRow> out = new ArrayList<>();
        int skipped = 0;
        for (List<String> r : rows.subList(1, rows.size())) {
            String name = cell(r, col, "name");
            if (name == null) {
                skipped++;
                continue;
            }

            Set<String> programs = new LinkedHashSet<>();
            String progs = cell(r, col, "programs");
            if (progs != null) {
                for (String p : progs.split(";")) {
                    if (!p.trim().isEmpty()) programs.add(p.trim());
                }
            }

            TeamType type = TeamType.COMMUNITY;
            if ("SCHOOL".equalsIgnoreCase(cell(r, col, "type"))) type = TeamType.SCHOOL;

            // key so re-importing the same csv updates instead of making duplicates
            String number = cell(r, col, "teamnumber");
            String key;
            if (number != null) {
                String prefix = programs.isEmpty() ? "" : programs.iterator().next() + "-";
                key = "CSV-" + prefix + number;
            } else {
                String zip = Objects.toString(cell(r, col, "zip"), "");
                key = "CSV-" + (name + "-" + zip).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            }

            out.add(new ImportRow(key, name, number, type, programs, cell(r, col, "schoolname"), cell(r, col, "city"),
                    States.toCode(cell(r, col, "state")), cell(r, col, "zip"), cell(r, col, "website"),
                    intOrNull(cell(r, col, "mingrade")), intOrNull(cell(r, col, "maxgrade"))));
        }
        upsertAll(out);

        String msg = "Imported/updated " + out.size() + " teams from CSV";
        if (skipped > 0) msg += " (" + skipped + " rows skipped)";
        return msg;
    }

    // ===== saving =====

    // saving 9000 teams one transaction at a time was super slow, so do them in chunks
    void upsertAll(List<ImportRow> rows) {
        for (int i = 0; i < rows.size(); i += CHUNK) {
            List<ImportRow> chunk = rows.subList(i, Math.min(rows.size(), i + CHUNK));

            // look up locations first, outside the transaction (might hit the network for weird zips)
            Map<ImportRow, ZipLocation> locs = new HashMap<>();
            for (ImportRow row : chunk) {
                ZipLocation loc = null;
                if (!TextUtil.isBlank(row.zip())) {
                    loc = zips.lookup(row.zip()).orElse(null);
                }
                if (loc == null && row.city() != null && row.state() != null) {
                    loc = zips.lookupCity(row.state(), row.city()).orElse(null);
                }
                if (loc != null) locs.put(row, loc);
            }

            tx.executeWithoutResult(s -> {
                for (ImportRow row : chunk) save(row, locs.get(row));
            });
            log.info("Import: saved {}/{} teams", Math.min(rows.size(), i + CHUNK), rows.size());
        }
    }

    private void save(ImportRow row, ZipLocation l) {
        Team t = teams.findByExternalKey(row.externalKey()).orElseGet(Team::new);
        if (t.isClaimed()) return; // team owns their profile now, don't touch it

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
            if (!TextUtil.isBlank(row.zip())) {
                t.setZip(row.zip().substring(0, Math.min(5, row.zip().length()))); // cut off zip+4
            } else {
                t.setZip(l.getZip());
            }
            t.setLatitude(l.getLatitude());
            t.setLongitude(l.getLongitude());
        }

        String site = TextUtil.normalizeUrl(fit(row.website()));
        // some teams just put firstinspires.org as their website lol
        if (isDirectorySite(site)) site = null;
        if (isDirectorySite(t.getWebsiteUrl())) t.setWebsiteUrl(null);
        if (site != null && !site.equals(t.getWebsiteUrl())) {
            t.setWebsiteUrl(site);
            t.setWebsiteAutoFound(false);
            t.setLastScrapedAt(null); // new site, check it again
        }
        teams.save(t);
    }

    // TBA had a team with 4 schools in the name (384 chars, with &amp; in it) and it crashed the import.
    // db columns are 255 so clean it up and cut it
    static String fit(String s) {
        if (s == null) return null;
        String t = s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim();
        if (t.length() <= 255) return t;
        return t.substring(0, 254).trim() + "…";
    }

    // sites that are directories, not a team's own website
    private static final Set<String> DIRECTORY_HOSTS = Set.of("firstinspires.org", "thebluealliance.com", "ftcscout.org",
            "robotevents.com", "events.vex.com", "vexrobotics.com", "statbotics.io",
            "frc-events.firstinspires.org", "ftc-events.firstinspires.org");

    static boolean isDirectorySite(String url) {
        if (url == null) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return true;
            host = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            for (String d : DIRECTORY_HOSTS) {
                if (host.equals(d) || host.endsWith("." + d)) return true;
            }
            return false;
        } catch (Exception e) {
            return true; // broken url, might as well not use it
        }
    }

    static TeamType guessType(String organization) {
        if (organization != null && SCHOOL_WORDS.matcher(organization).find()) {
            return TeamType.SCHOOL;
        }
        return TeamType.COMMUNITY;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        return TextUtil.trimToNull(v.asText());
    }

    private static String cell(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= row.size()) return null;
        return TextUtil.trimToNull(row.get(i));
    }

    private static Integer intOrNull(String s) {
        if (s == null) return null;
        if (s.equalsIgnoreCase("K")) return 0; // kindergarten
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return TextUtil.isBlank(a) ? b : a;
    }

    // simple csv parser. handles quotes, "" inside quotes, and commas/newlines inside quotes.
    // didn't want to add a whole library just for this
    static final class Csv {
        static List<List<String>> parse(String s) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;

            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cur.append('"'); // escaped quote
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        cur.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    row.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++; // windows line endings
                    row.add(cur.toString());
                    cur.setLength(0);
                    boolean blankLine = row.size() == 1 && row.get(0).isEmpty();
                    if (!blankLine) rows.add(row);
                    row = new ArrayList<>();
                } else {
                    cur.append(c);
                }
            }
            // last line might not end with a newline
            if (cur.length() > 0 || !row.isEmpty()) {
                row.add(cur.toString());
                rows.add(row);
            }
            return rows;
        }
    }
}
