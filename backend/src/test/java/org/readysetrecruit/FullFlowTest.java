package org.readysetrecruit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end API flow against the demo data (no network needed: demo ZIPs are pre-cached). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FullFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode read(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void searchApplyAndReview() throws Exception {
        // --- search ---
        JsonNode results = read(mvc.perform(get("/api/teams/search?zip=94040&radius=10")).andExpect(status().isOk()));
        assertTrue(results.size() >= 3);
        assertEquals("Gearheads Community Robotics", results.get(0).get("name").asText());
        assertEquals(0.0, results.get(0).get("distanceMiles").asDouble());
        long gearsId = results.get(0).get("id").asLong();
        assertTrue(results.get(0).get("hasOpenApplication").asBoolean());

        // grade filter: a kindergartner only sees the FLL Explore team nearby
        JsonNode k = read(mvc.perform(get("/api/teams/search?zip=94040&radius=10&grade=0")));
        assertEquals(1, k.size());
        // program filter
        JsonNode frc = read(mvc.perform(get("/api/teams/search?zip=94040&radius=30&program=FRC")));
        assertEquals(2, frc.size());
        mvc.perform(get("/api/teams/search?zip=00000")).andExpect(status().isBadRequest());

        // --- student applies ---
        String student = login("student@demo.org");
        JsonNode form = read(mvc.perform(get("/api/teams/" + gearsId + "/form")).andExpect(status().isOk()));
        long q1 = form.get("questions").get(0).get("id").asLong();
        long q2 = form.get("questions").get(1).get("id").asLong();

        // over the word limit -> rejected even for drafts
        String longAnswer = "word ".repeat(151);
        mvc.perform(auth(put("/api/applications/team/" + gearsId), student)
                        .content("{\"answers\":[{\"questionId\":" + q1 + ",\"response\":\"" + longAnswer + "\"}],\"submit\":false}"))
                .andExpect(status().isBadRequest());

        // draft OK
        mvc.perform(auth(put("/api/applications/team/" + gearsId), student)
                        .content("{\"answers\":[{\"questionId\":" + q1 + ",\"response\":\"I love building things\"}],\"submit\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));

        // submitting without required answers / grade fails
        mvc.perform(auth(put("/api/applications/team/" + gearsId), student)
                        .content("{\"answers\":[{\"questionId\":" + q1 + ",\"response\":\"I love building things\"}],\"submit\":true}"))
                .andExpect(status().isBadRequest());

        // ineligible grade fails
        mvc.perform(auth(put("/api/applications/team/" + gearsId), student)
                        .content("{\"grade\":3,\"parentEmail\":\"p@x.org\",\"answers\":[{\"questionId\":" + q1
                                + ",\"response\":\"x\"},{\"questionId\":" + q2 + ",\"response\":\"Programming\"}],\"submit\":true}"))
                .andExpect(status().isBadRequest());

        mvc.perform(auth(put("/api/applications/team/" + gearsId), student)
                        .content("{\"grade\":8,\"parentEmail\":\"p@x.org\",\"answers\":[{\"questionId\":" + q1
                                + ",\"response\":\"I love building things\"},{\"questionId\":" + q2 + ",\"response\":\"Programming\"}],\"submit\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));

        // can't edit after submitting
        mvc.perform(auth(put("/api/applications/team/" + gearsId), student)
                        .content("{\"answers\":[],\"submit\":false}"))
                .andExpect(status().isConflict());

        // students can't reach team endpoints
        mvc.perform(auth(get("/api/my/teams"), student)).andExpect(status().isForbidden());

        // --- team reviews ---
        String coach = login("team@demo.org");
        JsonNode apps = read(mvc.perform(auth(get("/api/my/teams/" + gearsId + "/applications"), coach)).andExpect(status().isOk()));
        assertEquals(1, apps.size());
        long appId = apps.get(0).get("id").asLong();
        mvc.perform(auth(patch("/api/my/teams/" + gearsId + "/applications/" + appId), coach)
                        .content("{\"status\":\"ACCEPTED\",\"teamNotes\":\"Great fit\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));

        // student sees status but not private notes
        JsonNode mine = read(mvc.perform(auth(get("/api/applications"), student)));
        assertEquals("ACCEPTED", mine.get(0).get("status").asText());
        assertTrue(mine.get(0).get("teamNotes").isNull());

        // coach can't manage a team they don't own
        long other = results.get(1).get("id").asLong();
        mvc.perform(auth(get("/api/my/teams/" + other + "/form"), coach)).andExpect(status().isForbidden());
    }

    @Test
    void formBuilderAndClaimFlow() throws Exception {
        // a new team account registers
        String reg = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"newcoach@x.org\",\"password\":\"longpassword\",\"name\":\"New Coach\",\"role\":\"TEAM\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String coach = json.readTree(reg).get("token").asText();

        // create a team (ZIP is pre-cached by demo data)
        JsonNode team = read(mvc.perform(auth(post("/api/my/teams"), coach).content("""
                {"name":"Byte Builders","type":"COMMUNITY","programs":["FTC"],"zip":"95112",
                 "websiteUrl":"bytebuilders.example","minGrade":6,"maxGrade":12,"recruiting":true}"""))
                .andExpect(status().isOk()));
        long teamId = team.get("id").asLong();
        assertEquals("San Jose", team.get("city").asText());
        assertEquals("https://bytebuilders.example", team.get("websiteUrl").asText());

        // can't open a form with no questions
        mvc.perform(auth(put("/api/my/teams/" + teamId + "/form"), coach)
                .content("{\"title\":\"Apply\",\"open\":true,\"questions\":[]}")).andExpect(status().isBadRequest());

        JsonNode f = read(mvc.perform(auth(put("/api/my/teams/" + teamId + "/form"), coach).content("""
                {"title":"Apply","open":true,"eligibleGrades":[9,10],"questions":[
                  {"prompt":"Why?","type":"LONG_TEXT","required":true,"wordLimit":100},
                  {"prompt":"Pick","type":"MULTIPLE_CHOICE","required":true,"options":"A\\nB"}]}"""))
                .andExpect(status().isOk()));
        long keepId = f.get("questions").get(0).get("id").asLong();

        // edit: keep q1 (renamed), drop q2, add q3
        JsonNode f2 = read(mvc.perform(auth(put("/api/my/teams/" + teamId + "/form"), coach).content("""
                {"title":"Apply","open":true,"eligibleGrades":[9,10],"questions":[
                  {"prompt":"New q","type":"SHORT_TEXT","required":false,"wordLimit":20},
                  {"id":%d,"prompt":"Why us?","type":"LONG_TEXT","required":true,"wordLimit":100}]}""".formatted(keepId)))
                .andExpect(status().isOk()));
        assertEquals(2, f2.get("questions").size());
        assertEquals(keepId, f2.get("questions").get(1).get("id").asLong());
        assertEquals("Why us?", f2.get("questions").get(1).get("prompt").asText());

        // claim an imported team, admin approves
        JsonNode lincoln = read(mvc.perform(get("/api/teams/search?q=RoboLions")));
        long lincolnId = lincoln.get(0).get("id").asLong();
        assertFalse(lincoln.get(0).get("claimed").asBoolean());
        mvc.perform(auth(post("/api/teams/" + lincolnId + "/claim"), coach).content("{\"message\":\"I'm the coach\"}"))
                .andExpect(status().isOk());

        String adminReg = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.org\",\"password\":\"longpassword\",\"name\":\"Admin\",\"role\":\"TEAM\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();
        String admin = json.readTree(adminReg).get("token").asText();
        mvc.perform(auth(get("/api/admin/claims"), coach)).andExpect(status().isForbidden());
        JsonNode claims = read(mvc.perform(auth(get("/api/admin/claims"), admin)).andExpect(status().isOk()));
        assertEquals(1, claims.size());
        mvc.perform(auth(post("/api/admin/claims/" + claims.get(0).get("id").asLong() + "/approve"), admin))
                .andExpect(status().isOk());
        JsonNode my = read(mvc.perform(auth(get("/api/my/teams"), coach)));
        assertEquals(2, my.size());

        // cron endpoint requires the token
        mvc.perform(post("/api/cron/scrape")).andExpect(status().isUnauthorized());
        // students must confirm age
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"kid@x.org\",\"password\":\"longpassword\",\"name\":\"Kid\",\"role\":\"STUDENT\"}"))
                .andExpect(status().isBadRequest());
    }
}
