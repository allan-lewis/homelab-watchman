package com.allanlewis.homelabwatchman.persist;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class ScanRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant startedAt;
    private Instant finishedAt;
    private int candidatesFound;
    private int prsOpened;

    public Long getId() {
        return id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public int getCandidatesFound() {
        return candidatesFound;
    }

    public void setCandidatesFound(int candidatesFound) {
        this.candidatesFound = candidatesFound;
    }

    public int getPrsOpened() {
        return prsOpened;
    }

    public void setPrsOpened(int prsOpened) {
        this.prsOpened = prsOpened;
    }
}
