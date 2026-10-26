package org.readysetrecruit.repo;

import org.readysetrecruit.model.ZipLocation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZipLocationRepository extends JpaRepository<ZipLocation, String> {
}
