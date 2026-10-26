package org.readysetrecruit.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

// the application a team builds. One form per team
@Entity
@Table(name = "application_forms")
public class ApplicationForm {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", unique = true)
    private Team team;

    private String title = "Join our team";
    @Column(length = 4000)
    private String intro;

    @Column(name = "is_open")
    private boolean open = false;
    private LocalDate deadline;

    // grades allowed to apply (0 = K). Empty = all grades
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "form_grades", joinColumns = @JoinColumn(name = "form_id"))
    @Column(name = "grade")
    private Set<Integer> eligibleGrades = new TreeSet<>();

    @OneToMany(mappedBy = "form", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    private List<FormQuestion> questions = new ArrayList<>();

    public boolean acceptsGrade(Integer grade) {
        return eligibleGrades.isEmpty() || (grade != null && eligibleGrades.contains(grade));
    }

    public boolean isAcceptingApplications() {
        return open && (deadline == null || !LocalDate.now().isAfter(deadline));
    }

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }
    public boolean isOpen() { return open; }
    public void setOpen(boolean open) { this.open = open; }
    public LocalDate getDeadline() { return deadline; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }
    public Set<Integer> getEligibleGrades() { return eligibleGrades; }
    public void setEligibleGrades(Set<Integer> eligibleGrades) { this.eligibleGrades = eligibleGrades; }
    public List<FormQuestion> getQuestions() { return questions; }
}
