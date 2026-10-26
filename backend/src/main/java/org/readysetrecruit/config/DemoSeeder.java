package org.readysetrecruit.config;

import org.readysetrecruit.model.*;
import org.readysetrecruit.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

// fake teams + accounts so there's something to click on when you run it locally
// logins: team@demo.org and student@demo.org, password is password123
// turned off on render with SEED_DEMO=false
@Component
@Order(1)
public class DemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final boolean enabled;
    private final TeamRepository teams;
    private final UserRepository users;
    private final ApplicationFormRepository forms;
    private final ZipLocationRepository zips;
    private final PasswordEncoder encoder;

    public DemoSeeder(@Value("${app.seed-demo}") boolean enabled, TeamRepository teams, UserRepository users,
                      ApplicationFormRepository forms, ZipLocationRepository zips, PasswordEncoder encoder) {
        this.enabled = enabled;
        this.teams = teams;
        this.users = users;
        this.forms = forms;
        this.zips = zips;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || teams.count() > 0) return;
        log.info("Seeding demo data (SEED_DEMO=true)");

        // save a few zips so the demo works without internet
        zips.saveAll(List.of(
                new ZipLocation("94040", 37.3855, -122.0881, "Mountain View", "CA"),
                new ZipLocation("94301", 37.4443, -122.1598, "Palo Alto", "CA"),
                new ZipLocation("95014", 37.3180, -122.0449, "Cupertino", "CA"),
                new ZipLocation("94086", 37.3712, -122.0389, "Sunnyvale", "CA"),
                new ZipLocation("95051", 37.3483, -121.9844, "Santa Clara", "CA"),
                new ZipLocation("95112", 37.3440, -121.8830, "San Jose", "CA")));

        User coach = user("team@demo.org", "Coach Rivera", Role.TEAM);
        user("student@demo.org", "Sam Student", Role.STUDENT);

        Team gears = team("Gearheads Community Robotics", "11115", TeamType.COMMUNITY, Set.of("FTC", "FLL Challenge"),
                "94040", 37.3855, -122.0881, "Mountain View", 4, 12, true, coach,
                "A community team open to students from any school. We build, code and compete in FTC and FLL, "
                        + "and mentor younger teams. No experience needed!");
        gears.setMeetingInfo("Tuesdays 6-8pm and Saturdays 10am-2pm at the Mountain View makerspace");
        gears.setCostInfo("$150/season, scholarships available");
        gears.setContactEmail("hello@gearheads.example");

        team("Lincoln High RoboLions", "8404", TeamType.SCHOOL, Set.of("FRC"), "94301", 37.4443, -122.1598,
                "Palo Alto", 9, 12, true, null, "Lincoln High's FRC team.").setSchoolName("Lincoln High School");
        team("Cupertino Circuit Breakers", null, TeamType.COMMUNITY, Set.of("VEX IQ", "VEX V5"), "95014",
                37.3180, -122.0449, "Cupertino", 5, 10, false, null, null).setWebsiteUrl("https://example.org/");
        team("Sunnyvale Junior Botbuilders", null, TeamType.COMMUNITY, Set.of("FLL Explore", "FLL Challenge"), "94086",
                37.3712, -122.0389, "Sunnyvale", 0, 8, true, null,
                "LEGO robotics for elementary and middle schoolers.");
        team("Santa Clara Steel Sharks", "5940", TeamType.SCHOOL, Set.of("FRC"), "95051", 37.3483, -121.9844,
                "Santa Clara", 9, 12, false, null, null);

        ApplicationForm f = new ApplicationForm();
        f.setTeam(gears);
        f.setTitle("Join the Gearheads for the 2026-27 season");
        f.setIntro("Tell us a bit about yourself. There are no wrong answers - we care about curiosity and teamwork.");
        f.setOpen(true);
        f.setDeadline(LocalDate.now().plusMonths(2));
        f.getEligibleGrades().addAll(Set.of(6, 7, 8, 9, 10, 11, 12));
        f.getQuestions().add(q(f, 0, "Why do you want to join a robotics team?", QuestionType.LONG_TEXT, true, 150, null));
        f.getQuestions().add(q(f, 1, "Which area interests you most?", QuestionType.MULTIPLE_CHOICE, true, null,
                "Building / mechanical\nProgramming\nCAD / design\nOutreach / business\nNot sure yet"));
        f.getQuestions().add(q(f, 2, "Any prior robotics or coding experience?", QuestionType.SHORT_TEXT, false, 50, null));
        forms.save(f);
    }

    private User user(String email, String name, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setName(name);
        u.setRole(role);
        u.setPasswordHash(encoder.encode("password123"));
        return users.save(u);
    }

    private Team team(String name, String number, TeamType type, Set<String> programs, String zip, double lat, double lng,
                      String city, int minG, int maxG, boolean recruiting, User owner, String description) {
        Team t = new Team();
        t.setName(name);
        t.setTeamNumber(number);
        t.setType(type);
        t.getPrograms().addAll(programs);
        t.setZip(zip);
        t.setLatitude(lat);
        t.setLongitude(lng);
        t.setCity(city);
        t.setState("CA");
        t.setMinGrade(minG);
        t.setMaxGrade(maxG);
        t.setRecruiting(recruiting);
        t.setOwner(owner);
        t.setDescription(description);
        t.setSource(owner == null ? TeamSource.IMPORTED : TeamSource.SELF);
        if (owner == null) t.setExternalKey("DEMO-" + name.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        return teams.save(t);
    }

    private FormQuestion q(ApplicationForm f, int pos, String prompt, QuestionType type, boolean required,
                           Integer wordLimit, String options) {
        FormQuestion q = new FormQuestion();
        q.setForm(f);
        q.setPosition(pos);
        q.setPrompt(prompt);
        q.setType(type);
        q.setRequired(required);
        q.setWordLimit(wordLimit);
        q.setOptions(options);
        return q;
    }
}
