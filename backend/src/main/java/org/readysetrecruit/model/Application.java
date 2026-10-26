package org.readysetrecruit.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "applications", uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "student_id"}))
public class Application {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Team team;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status = ApplicationStatus.DRAFT;

    private Integer grade;
    private String parentEmail;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "application_answers", joinColumns = @JoinColumn(name = "application_id"))
    @OrderColumn(name = "idx")
    private List<Answer> answers = new ArrayList<>();

    @Column(length = 4000)
    private String teamNotes;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private Instant submittedAt;

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }
    public User getStudent() { return student; }
    public void setStudent(User student) { this.student = student; }
    public ApplicationStatus getStatus() { return status; }
    public void setStatus(ApplicationStatus status) { this.status = status; }
    public Integer getGrade() { return grade; }
    public void setGrade(Integer grade) { this.grade = grade; }
    public String getParentEmail() { return parentEmail; }
    public void setParentEmail(String parentEmail) { this.parentEmail = parentEmail; }
    public List<Answer> getAnswers() { return answers; }
    public void setAnswers(List<Answer> answers) { this.answers = answers; }
    public String getTeamNotes() { return teamNotes; }
    public void setTeamNotes(String teamNotes) { this.teamNotes = teamNotes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
}
