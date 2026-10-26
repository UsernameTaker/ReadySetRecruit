package org.readysetrecruit.repo;

import org.readysetrecruit.model.Team;
import org.readysetrecruit.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long>, JpaSpecificationExecutor<Team> {
    List<Team> findByOwnerOrderByNameAsc(User owner);
    Optional<Team> findByExternalKey(String externalKey);
    long countByExternalKeyStartingWith(String prefix);

    /** Teams with a website that hasn't been checked recently. */
    @Query("""
            select t from Team t
            where t.websiteUrl is not null and t.websiteUrl <> ''
              and (t.lastScrapedAt is null or t.lastScrapedAt < :before)
            order by t.lastScrapedAt asc nulls first
            """)
    List<Team> findDueForScrape(@Param("before") Instant before, Pageable page);

    /** Unclaimed teams with no website that we haven't searched for recently. */
    @Query("""
            select t from Team t
            where (t.websiteUrl is null or t.websiteUrl = '') and t.owner is null
              and (t.lastSearchedAt is null or t.lastSearchedAt < :before)
            order by t.lastSearchedAt asc nulls first
            """)
    List<Team> findDueForWebsiteSearch(@Param("before") Instant before, Pageable page);

    long countByOwnerIsNotNull();
}
