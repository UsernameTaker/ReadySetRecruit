package org.readysetrecruit.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "teams", indexes = {
        @Index(name = "idx_team_latlng", columnList = "latitude,longitude"),
        @Index(name = "idx_team_zip", columnList = "zip"),
        @Index(name = "idx_team_external", columnList = "external_key", unique = true)
})
public class Team {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Official team number, e.g. FRC 254 / FTC 11115. Optional. */
    private String teamNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TeamType type = TeamType.COMMUNITY;

    /** Programs the team competes in: FLL, FTC, FRC, VEX IQ, VRC, Other... */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "team_programs", joinColumns = @JoinColumn(name = "team_id"))
    @Column(name = "program")
    private Set<String> programs = new LinkedHashSet<>();

    @Column(length = 4000)
    private String description;
    private String schoolName;
    private String city;
    private String state;
    private String zip;
    private Double latitude;
    private Double longitude;

    private String websiteUrl;
    private String contactEmail;
    @Column(length = 1000)
    private String meetingInfo;
    private String costInfo;

    /** Grade range the team accepts; 0 = K. Null = not specified. */
    private Integer minGrade;
    private Integer maxGrade;
    private boolean recruiting = false;

    @ManyToOne(fetch = FetchType.LAZY)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TeamSource source = TeamSource.SELF;
    /** Unique key for imported teams, e.g. "FRC-254" (lets imports upsert). */
    private String externalKey;

    // ---- scraper results ----
    @Enumerated(EnumType.STRING)
    private WebsiteStatus websiteStatus = WebsiteStatus.UNKNOWN;
    /** True when the website was found by automated search and not confirmed by a human. */
    private boolean websiteAutoFound = false;
    private String websiteTitle;
    @Column(length = 1000)
    private String websiteDescription;
    /** A "join us / apply / recruiting" link found on the team's own website. */
    private String joinUrl;
    private int scrapeFailures = 0;
    private Instant lastScrapedAt;
    private Instant lastSearchedAt;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public boolean isClaimed() { return owner != null; }

    /** What students see: only websites that have been checked and actually open. */
    public String getVisibleWebsiteUrl() {
        return websiteStatus == WebsiteStatus.OK || websiteStatus == WebsiteStatus.BLOCKED_BY_ROBOTS ? websiteUrl : null;
    }

    public String getVisibleJoinUrl() { return websiteStatus == WebsiteStatus.OK ? joinUrl : null; }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTeamNumber() { return teamNumber; }
    public void setTeamNumber(String teamNumber) { this.teamNumber = teamNumber; }
    public TeamType getType() { return type; }
    public void setType(TeamType type) { this.type = type; }
    public Set<String> getPrograms() { return programs; }
    public void setPrograms(Set<String> programs) { this.programs = programs; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSchoolName() { return schoolName; }
    public void setSchoolName(String schoolName) { this.schoolName = schoolName; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getZip() { return zip; }
    public void setZip(String zip) { this.zip = zip; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getWebsiteUrl() { return websiteUrl; }
    public void setWebsiteUrl(String websiteUrl) { this.websiteUrl = websiteUrl; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getMeetingInfo() { return meetingInfo; }
    public void setMeetingInfo(String meetingInfo) { this.meetingInfo = meetingInfo; }
    public String getCostInfo() { return costInfo; }
    public void setCostInfo(String costInfo) { this.costInfo = costInfo; }
    public Integer getMinGrade() { return minGrade; }
    public void setMinGrade(Integer minGrade) { this.minGrade = minGrade; }
    public Integer getMaxGrade() { return maxGrade; }
    public void setMaxGrade(Integer maxGrade) { this.maxGrade = maxGrade; }
    public boolean isRecruiting() { return recruiting; }
    public void setRecruiting(boolean recruiting) { this.recruiting = recruiting; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public TeamSource getSource() { return source; }
    public void setSource(TeamSource source) { this.source = source; }
    public String getExternalKey() { return externalKey; }
    public void setExternalKey(String externalKey) { this.externalKey = externalKey; }
    public WebsiteStatus getWebsiteStatus() { return websiteStatus; }
    public void setWebsiteStatus(WebsiteStatus websiteStatus) { this.websiteStatus = websiteStatus; }
    public boolean isWebsiteAutoFound() { return websiteAutoFound; }
    public void setWebsiteAutoFound(boolean websiteAutoFound) { this.websiteAutoFound = websiteAutoFound; }
    public String getWebsiteTitle() { return websiteTitle; }
    public void setWebsiteTitle(String websiteTitle) { this.websiteTitle = websiteTitle; }
    public String getWebsiteDescription() { return websiteDescription; }
    public void setWebsiteDescription(String websiteDescription) { this.websiteDescription = websiteDescription; }
    public String getJoinUrl() { return joinUrl; }
    public void setJoinUrl(String joinUrl) { this.joinUrl = joinUrl; }
    public int getScrapeFailures() { return scrapeFailures; }
    public void setScrapeFailures(int scrapeFailures) { this.scrapeFailures = scrapeFailures; }
    public Instant getLastScrapedAt() { return lastScrapedAt; }
    public void setLastScrapedAt(Instant lastScrapedAt) { this.lastScrapedAt = lastScrapedAt; }
    public Instant getLastSearchedAt() { return lastSearchedAt; }
    public void setLastSearchedAt(Instant lastSearchedAt) { this.lastSearchedAt = lastSearchedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
