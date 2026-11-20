package com.allanlewis.homelabwatchman.persist;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DependencyStateRepository extends JpaRepository<DependencyState, Long> {

    // Keyed by (identifier, file) rather than identifier alone, since the
    // same image can be pinned in more than one file. Note: twingate/connector
    // appears twice in the *same* file (two identical container blocks) -
    // those two candidates collapse into one row here, which loses nothing
    // since both instances always carry identical values.
    Optional<DependencyState> findByIdentifierAndFile(String identifier, String file);
}
