package org.readysetrecruit.model;

import jakarta.persistence.*;
import java.time.Instant;

// a team account asking to take over an imported (unclaimed) team listing. Reviewed by an admin
@Entity
@Table(name = "claim_requests")
public class ClaimRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Team team;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Column(length = 2000)
    private String message;

    @Enumerated(EnumType.STRING)
    private ClaimStatus status = ClaimStatus.PENDING;

    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public ClaimStatus getStatus() { return status; }
    public void setStatus(ClaimStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
