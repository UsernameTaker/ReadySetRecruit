package org.readysetrecruit.repo;

import org.readysetrecruit.model.ClaimRequest;
import org.readysetrecruit.model.ClaimStatus;
import org.readysetrecruit.model.Team;
import org.readysetrecruit.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaimRequestRepository extends JpaRepository<ClaimRequest, Long> {
    List<ClaimRequest> findByStatusOrderByCreatedAtAsc(ClaimStatus status);
    boolean existsByTeamAndUserAndStatus(Team team, User user, ClaimStatus status);
    List<ClaimRequest> findByTeamAndStatus(Team team, ClaimStatus status);
}
