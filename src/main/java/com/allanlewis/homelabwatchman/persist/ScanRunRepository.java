package com.allanlewis.homelabwatchman.persist;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanRunRepository extends JpaRepository<ScanRun, Long> {
}
