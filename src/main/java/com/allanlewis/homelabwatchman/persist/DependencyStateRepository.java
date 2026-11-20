package com.allanlewis.homelabwatchman.persist;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DependencyStateRepository extends JpaRepository<DependencyState, Long> {

    Optional<DependencyState> findByIdentifier(String identifier);
}
