package org.readysetrecruit.service;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.readysetrecruit.config.ApiException;
import org.readysetrecruit.dto.TeamDtos.*;
import org.readysetrecruit.model.*;
import org.readysetrecruit.repo.ApplicationFormRepository;
import org.readysetrecruit.repo.ClaimRequestRepository;
import org.readysetrecruit.repo.TeamRepository;
import org.readysetrecruit.repo.UserRepository;
import org.readysetrecruit.scrape.ScrapeService;
import org.readysetrecruit.scrape.WebsiteScraper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TeamService {
    public static final List<String> PROGRAMS = List.of("FLL Explore", "FLL Challenge", "FTC", "FRC",
            "VEX IQ", "VEX V5", "VEX U", "Other");
    private static final int MAX_RESULTS = 200;

    private final TeamRepository teams;
    private final ApplicationFormRepository forms;
    private final ClaimRequestRepository claims;
    private final UserRepository users;
    private final ZipService zips;
    private final WebsiteScraper scraper;
    private final boolean verifyWebsites;

    public TeamService(TeamRepository teams, ApplicationFormRepository forms, ClaimRequestRepository claims,
                       UserRepository users, ZipService zips, WebsiteScraper scraper,
                       @Value("${app.scraper.verify-on-save}") boolean verifyWebsites) {
        this.teams = teams;
        this.forms = forms;
        this.claims = claims;
        this.users = users;
        this.zips = zips;
        this.scraper = scraper;
        this.verifyWebsites = verifyWebsites;
    }

    public record SearchParams(String zip, Integer radiusMiles, Integer grade, String program, TeamType type,
                               Boolean recruitingOnly, Boolean openApplicationsOnly, String state, String q) {}

    @Transactional(readOnly = true)
    public List<TeamSummary> search(SearchParams p) {
        ZipLocation origin = null;
        int radius = p.radiusMiles() == null ? 25 : Math.max(1, Math.min(p.radiusMiles(), 250));
        if (!TextUtil.isBlank(p.zip())) {
            origin = zips.lookup(p.zip()).orElseThrow(() -> ApiException.badRequest("We couldn't find that ZIP code"));
        }

        final ZipLocation o = origin;
        Specification<Team> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (o != null) {
                // rough box around the zip first (fast in the db), exact miles get calculated after
                double dLat = radius / 69.0;
                double dLng = radius / (69.0 * Math.max(0.2, Math.cos(Math.toRadians(o.getLatitude()))));
                preds.add(cb.between(root.get("latitude"), o.getLatitude() - dLat, o.getLatitude() + dLat));
                preds.add(cb.between(root.get("longitude"), o.getLongitude() - dLng, o.getLongitude() + dLng));
            } else if (!TextUtil.isBlank(p.state())) {
                preds.add(cb.equal(cb.upper(root.get("state")), p.state().trim().toUpperCase()));
            }
            if (p.type() != null) preds.add(cb.equal(root.get("type"), p.type()));
            if (Boolean.TRUE.equals(p.recruitingOnly())) preds.add(cb.isTrue(root.get("recruiting")));
            if (p.grade() != null) {
                preds.add(cb.or(cb.isNull(root.get("minGrade")), cb.le(root.get("minGrade"), p.grade())));
                preds.add(cb.or(cb.isNull(root.get("maxGrade")), cb.ge(root.get("maxGrade"), p.grade())));
            }
            if (!TextUtil.isBlank(p.program())) {
                Join<Team, String> prog = root.join("programs");
                preds.add(cb.equal(cb.upper(prog), p.program().trim().toUpperCase()));
                query.distinct(true);
            }
            if (!TextUtil.isBlank(p.q())) {
                String like = "%" + p.q().trim().toLowerCase() + "%";
                preds.add(cb.or(cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("teamNumber")), like),
                        cb.like(cb.lower(root.get("schoolName")), like),
                        cb.like(cb.lower(root.get("city")), like)));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };

        int fetchLimit = o != null ? 2000 : MAX_RESULTS;
        List<Team> found = teams.findAll(spec, PageRequest.of(0, fetchLimit, Sort.by("name"))).getContent();

        Map<Long, ApplicationForm> formsByTeam = found.isEmpty() ? Map.of()
                : forms.findByTeamIds(found.stream().map(Team::getId).toList()).stream()
                    .collect(Collectors.toMap(f -> f.getTeam().getId(), Function.identity()));

        List<TeamSummary> out = new ArrayList<>();
        for (Team t : found) {
            Double dist = null;
            if (o != null) {
                if (t.getLatitude() == null || t.getLongitude() == null) continue;
                dist = ZipService.distanceMiles(o.getLatitude(), o.getLongitude(), t.getLatitude(), t.getLongitude());
                if (dist > radius) continue;
                dist = Math.round(dist * 10) / 10.0;
            }
            ApplicationForm f = formsByTeam.get(t.getId());
            boolean open = f != null && f.isAcceptingApplications() && (p.grade() == null || f.acceptsGrade(p.grade()));
            if (Boolean.TRUE.equals(p.openApplicationsOnly()) && !open) continue;
            out.add(summary(t, dist, open));
        }
        if (o != null) out.sort(Comparator.comparing(TeamSummary::distanceMiles));
        return out.size() > MAX_RESULTS ? out.subList(0, MAX_RESULTS) : out;
    }

    private TeamSummary summary(Team t, Double dist, boolean openApp) {
        String blurb = !TextUtil.isBlank(t.getDescription()) ? t.getDescription() : t.getWebsiteDescription();
        return new TeamSummary(t.getId(), t.getName(), t.getTeamNumber(), t.getType(), t.getPrograms(),
                t.getCity(), t.getState(), t.getZip(), dist, t.getMinGrade(), t.getMaxGrade(), t.isRecruiting(),
                t.isClaimed(), t.getVisibleWebsiteUrl(), t.getVisibleJoinUrl(), openApp, TextUtil.snippet(blurb, 220));
    }

    @Transactional(readOnly = true)
    public TeamDetail detail(Long id) {
        Team t = teams.findById(id).orElseThrow(() -> ApiException.notFound("Team"));
        return toDetail(t, false);
    }

    // owners see whatever they typed in, everyone else only sees the website if it works
    TeamDetail toDetail(Team t) { return toDetail(t, true); }

    TeamDetail toDetail(Team t, boolean forOwner) {
        FormSummary fs = forms.findByTeam(t)
                .map(f -> new FormSummary(f.isAcceptingApplications(), f.getTitle(), f.getDeadline(),
                        f.getEligibleGrades(), f.getQuestions().size()))
                .orElse(null);
        return new TeamDetail(t.getId(), t.getName(), t.getTeamNumber(), t.getType(), t.getPrograms(),
                t.getDescription(), t.getSchoolName(), t.getCity(), t.getState(), t.getZip(),
                forOwner ? t.getWebsiteUrl() : t.getVisibleWebsiteUrl(), t.isWebsiteAutoFound(), t.getWebsiteStatus(),
                t.getWebsiteTitle(), t.getWebsiteDescription(), forOwner ? t.getJoinUrl() : t.getVisibleJoinUrl(), t.getContactEmail(), t.getMeetingInfo(), t.getCostInfo(),
                t.getMinGrade(), t.getMaxGrade(), t.isRecruiting(), t.isClaimed(), t.getSource(), fs);
    }

    // ---------------- Team owners ----------------

    @Transactional(readOnly = true)
    public List<TeamDetail> myTeams(User owner) {
        User u = users.getReferenceById(owner.getId());
        return teams.findByOwnerOrderByNameAsc(u).stream().map(this::toDetail).toList();
    }

    @Transactional
    public TeamDetail create(User owner, TeamUpsert req) {
        Team t = new Team();
        t.setOwner(users.getReferenceById(owner.getId()));
        t.setSource(TeamSource.SELF);
        apply(t, req);
        teams.save(t);
        return toDetail(t);
    }

    @Transactional
    public TeamDetail update(User user, Long teamId, TeamUpsert req) {
        Team t = requireManagedTeam(user, teamId);
        apply(t, req);
        return toDetail(t);
    }

    private void apply(Team t, TeamUpsert r) {
        if (r.minGrade() != null && r.maxGrade() != null && r.minGrade() > r.maxGrade())
            throw ApiException.badRequest("Minimum grade can't be above maximum grade");
        t.setName(r.name().trim());
        t.setTeamNumber(TextUtil.trimToNull(r.teamNumber()));
        t.setType(r.type());
        Set<String> programs = new LinkedHashSet<>();
        if (r.programs() != null) r.programs().stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(programs::add);
        t.getPrograms().clear();
        t.getPrograms().addAll(programs);
        t.setDescription(TextUtil.trimToNull(r.description()));
        t.setSchoolName(TextUtil.trimToNull(r.schoolName()));
        t.setContactEmail(TextUtil.trimToNull(r.contactEmail()));
        t.setMeetingInfo(TextUtil.trimToNull(r.meetingInfo()));
        t.setCostInfo(TextUtil.trimToNull(r.costInfo()));
        t.setMinGrade(r.minGrade());
        t.setMaxGrade(r.maxGrade());
        t.setRecruiting(r.recruiting());

        String newUrl = TextUtil.normalizeUrl(r.websiteUrl());
        if (!Objects.equals(newUrl, t.getWebsiteUrl())) {
            t.setWebsiteUrl(newUrl);
            t.setWebsiteAutoFound(false);
            t.setWebsiteStatus(WebsiteStatus.UNKNOWN);
            t.setLastScrapedAt(null);
            t.setJoinUrl(null);
            t.setScrapeFailures(0);
            if (newUrl != null && verifyWebsites) {
                // check it right now so a typo or dead link never ends up on their page
                WebsiteScraper.Result check = scraper.scrape(newUrl);
                if (!check.opensForPeople())
                    throw ApiException.badRequest("We couldn't open " + newUrl
                            + ". Check the address (try it in your browser), or leave the website blank for now.");
                ScrapeService.apply(t, check);
            }
        } else if (newUrl != null) {
            t.setWebsiteAutoFound(false); // a human saved the profile with this URL -> confirmed
        }

        String zip = r.zip().trim();
        if (!zip.equals(t.getZip()) || t.getLatitude() == null) {
            ZipLocation loc = zips.lookup(zip).orElseThrow(() -> ApiException.badRequest("We couldn't find that ZIP code"));
            t.setZip(zip);
            t.setLatitude(loc.getLatitude());
            t.setLongitude(loc.getLongitude());
            if (TextUtil.isBlank(r.city())) t.setCity(loc.getCity());
            if (TextUtil.isBlank(r.state())) t.setState(loc.getState());
        }
        if (!TextUtil.isBlank(r.city())) t.setCity(r.city().trim());
        if (!TextUtil.isBlank(r.state())) t.setState(r.state().trim().toUpperCase());
    }

    // make sure this user actually runs this team (admins can edit any team)
    public Team requireManagedTeam(User user, Long teamId) {
        Team t = teams.findById(teamId).orElseThrow(() -> ApiException.notFound("Team"));
        boolean owner = t.getOwner() != null && t.getOwner().getId().equals(user.getId());
        if (!owner && user.getRole() != Role.ADMIN) throw ApiException.forbidden("You don't manage this team");
        return t;
    }

    // ---------------- Claiming imported teams ----------------

    @Transactional
    public void requestClaim(User user, Long teamId, String message) {
        if (user.getRole() == Role.STUDENT) throw ApiException.forbidden("Only team accounts can claim a team");
        Team t = teams.findById(teamId).orElseThrow(() -> ApiException.notFound("Team"));
        if (t.isClaimed()) throw ApiException.conflict("This team has already been claimed");
        User u = users.getReferenceById(user.getId());
        if (claims.existsByTeamAndUserAndStatus(t, u, ClaimStatus.PENDING))
            throw ApiException.conflict("You already have a pending claim for this team");
        ClaimRequest c = new ClaimRequest();
        c.setTeam(t);
        c.setUser(u);
        c.setMessage(TextUtil.trimToNull(message));
        claims.save(c);
    }

    @Transactional(readOnly = true)
    public List<ClaimDto> pendingClaims() {
        return claims.findByStatusOrderByCreatedAtAsc(ClaimStatus.PENDING).stream().map(ClaimDto::of).toList();
    }

    @Transactional
    public void decideClaim(Long claimId, boolean approve) {
        ClaimRequest c = claims.findById(claimId).orElseThrow(() -> ApiException.notFound("Claim"));
        if (c.getStatus() != ClaimStatus.PENDING) throw ApiException.conflict("Claim already decided");
        if (!approve) {
            c.setStatus(ClaimStatus.REJECTED);
            return;
        }
        Team t = c.getTeam();
        if (t.isClaimed()) throw ApiException.conflict("Team already claimed");
        t.setOwner(c.getUser());
        c.setStatus(ClaimStatus.APPROVED);
        // if other people also tried to claim this team, reject theirs
        claims.findByTeamAndStatus(t, ClaimStatus.PENDING).stream()
                .filter(other -> !other.getId().equals(c.getId()))
                .forEach(other -> other.setStatus(ClaimStatus.REJECTED));
    }
}
