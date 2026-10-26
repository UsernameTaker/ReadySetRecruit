package org.readysetrecruit.scrape;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.readysetrecruit.model.Team;
import org.readysetrecruit.model.TeamType;
import org.readysetrecruit.service.GeoData;
import org.readysetrecruit.service.TextUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScraperUnitTests {

    @Test
    void robotsLongestRuleWins() {
        RobotsTxt r = RobotsTxt.parse("""
                User-agent: *
                Disallow: /private
                Allow: /private/join
                User-agent: BadBot
                Disallow: /
                """, "ReadySetRecruitBot/0.1");
        assertTrue(r.allows("/"));
        assertFalse(r.allows("/private/x"));
        assertTrue(r.allows("/private/join"));
    }

    @Test
    void robotsOwnAgentSection() {
        RobotsTxt r = RobotsTxt.parse("User-agent: readysetrecruitbot\nDisallow: /\n\nUser-agent: *\nDisallow:\n", "ReadySetRecruitBot/0.1");
        assertFalse(r.allows("/"));
    }

    @Test
    void findsJoinLinkAndEmail() {
        Document doc = Jsoup.parse("""
                <html><body>
                  <a href="/about">About</a>
                  <a href="https://instagram.com/team">Join us on Instagram</a>
                  <a href="/join-the-team">Join the team!</a>
                  <a href="mailto:coach@team.org">Email</a>
                </body></html>""", "https://team.org/");
        assertEquals("https://team.org/join-the-team", WebsiteScraper.findJoinLink(doc).orElseThrow());
        assertEquals("coach@team.org", WebsiteScraper.findEmail(doc).orElseThrow());
    }

    @Test
    void ignoresSiteBuilderSignUpLinks() {
        Document doc = Jsoup.parse("""
                <a href="https://accounts.google.com/lifecycle/flows/signup?continue=x">Sign up</a>
                <a href="/login">Sign up / Log in</a>
                <a href="https://forms.gle/abc">Interest form</a>""", "https://sites.google.com/view/team/");
        assertEquals("https://forms.gle/abc", WebsiteScraper.findJoinLink(doc).orElseThrow());
    }

    @Test
    void noJoinLinkWhenNothingMatches() {
        Document doc = Jsoup.parse("<a href='/about'>About</a><a href='/sponsors'>Sponsors</a>", "https://team.org/");
        assertTrue(WebsiteScraper.findJoinLink(doc).isEmpty());
    }

    @Test
    void searchMatchingIsConservative() {
        Team t = new Team();
        t.setName("Steel Sharks");
        t.setTeamNumber("5940");
        assertTrue(WebsiteSearch.looksLikeTeam(t, "team 5940 steel sharks robotics"));
        assertFalse(WebsiteSearch.looksLikeTeam(t, "team 59401 robotics"));
        Team n = new Team();
        n.setName("Gearheads Community Robotics");
        assertTrue(WebsiteSearch.looksLikeTeam(n, "gearheads community robotics club home"));
        assertFalse(WebsiteSearch.looksLikeTeam(n, "gearheads car club"));
    }

    @Test
    void csvParsing() {
        List<List<String>> rows = ImportService.Csv.parse("name,city\n\"Bots, Inc\",\"San \"\"Jose\"\"\"\r\nA,B\n");
        assertEquals(3, rows.size());
        assertEquals("Bots, Inc", rows.get(1).get(0));
        assertEquals("San \"Jose\"", rows.get(1).get(1));
    }

    @Test
    void wordCount() {
        assertEquals(0, TextUtil.wordCount("   "));
        assertEquals(3, TextUtil.wordCount(" one two\nthree "));
        assertEquals("https://x.org", TextUtil.normalizeUrl(" x.org "));
        assertEquals("CA", States.toCode("California"));
        assertEquals("CA", States.toCode("ca"));
    }

    @Test
    void guessesSchoolVsCommunityTeams() {
        assertEquals(TeamType.SCHOOL, ImportService.guessType("Stuyvesant High School"));
        assertEquals(TeamType.SCHOOL, ImportService.guessType("Brophy College Preparatory"));
        assertEquals(TeamType.COMMUNITY, ImportService.guessType("FTC1 Team Unlimited 4-H Club"));
        assertEquals(TeamType.COMMUNITY, ImportService.guessType("Family/Community"));
        assertEquals(TeamType.COMMUNITY, ImportService.guessType(null));
    }

    @Test
    void importedTextIsUnescapedAndShortened() {
        assertEquals("A & B School", ImportService.fit("A &amp; B School"));
        assertEquals(255, ImportService.fit("x".repeat(400)).length());
        assertNull(ImportService.fit(null));
    }

    @Test
    void bundledGeoDataFindsZipsAndCities() {
        GeoData geo = new GeoData();
        assertEquals("Mountain View", geo.zip("94040").orElseThrow().city());
        assertTrue(geo.city("MN", "Eagan").isPresent());          // suburb, not a postal place name
        assertTrue(geo.city("MO", "St. Louis").isPresent());      // "St." vs "Saint"
        assertTrue(geo.city("WI", "Galesville, WI").isPresent()); // state repeated in the city field
        assertTrue(geo.city("CA", "Nowhereville").isEmpty());
    }

    @Test
    void detectsParkedDomains() {
        assertTrue(WebsiteScraper.isParked(Jsoup.parse("<title>team123.org</title><h1>This domain is for sale!</h1>")));
        assertTrue(WebsiteScraper.isParked(Jsoup.parse("<p>Looks like this domain isn't connected to a website yet!</p>")));
        assertFalse(WebsiteScraper.isParked(Jsoup.parse("<title>Team 123 Robotics</title><p>Join our team!</p>")));
    }

    @Test
    void studentsOnlySeeWebsitesThatWork() {
        Team t = new Team();
        t.setWebsiteUrl("https://team.org");
        t.setJoinUrl("https://team.org/join");
        assertNull(t.getVisibleWebsiteUrl()); // not checked yet

        ScrapeService.apply(t, new WebsiteScraper.Result(true, false, false, "https://team.org", "Team", null, "https://team.org/join", null));
        assertEquals("https://team.org", t.getVisibleWebsiteUrl());
        assertEquals("https://team.org/join", t.getVisibleJoinUrl());

        ScrapeService.apply(t, WebsiteScraper.Result.unreachable());
        assertNull(t.getVisibleWebsiteUrl());
        assertNull(t.getVisibleJoinUrl());

        ScrapeService.apply(t, WebsiteScraper.Result.refused()); // blocks bots, but opens in a browser
        assertEquals("https://team.org", t.getVisibleWebsiteUrl());
    }

    @Test
    void ignoresDirectorySitesAsTeamWebsites() {
        assertTrue(ImportService.isDirectorySite("http://www.firstinspires.org/"));
        assertTrue(ImportService.isDirectorySite("https://ftc-events.firstinspires.org/team/123"));
        assertFalse(ImportService.isDirectorySite("https://team254.com"));
    }
}
