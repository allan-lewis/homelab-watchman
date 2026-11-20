package com.allanlewis.homelabwatchman.persist;

import com.allanlewis.homelabwatchman.plan.UpdateKind;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * Latest known state for one tracked dependency, kept across runs so a
 * future GUI can render history without needing its own storage design.
 */
@Entity
public class DependencyState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String identifier;
    private String file;
    private String currentValue;
    private String latestKnownValue;

    @Enumerated(EnumType.STRING)
    private UpdateKind updateKind;

    private String lastPrUrl;
    private String lastPrStatus;
    private Instant lastCheckedAt;

    public Long getId() {
        return id;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public String getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(String currentValue) {
        this.currentValue = currentValue;
    }

    public String getLatestKnownValue() {
        return latestKnownValue;
    }

    public void setLatestKnownValue(String latestKnownValue) {
        this.latestKnownValue = latestKnownValue;
    }

    public UpdateKind getUpdateKind() {
        return updateKind;
    }

    public void setUpdateKind(UpdateKind updateKind) {
        this.updateKind = updateKind;
    }

    public String getLastPrUrl() {
        return lastPrUrl;
    }

    public void setLastPrUrl(String lastPrUrl) {
        this.lastPrUrl = lastPrUrl;
    }

    public String getLastPrStatus() {
        return lastPrStatus;
    }

    public void setLastPrStatus(String lastPrStatus) {
        this.lastPrStatus = lastPrStatus;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }
}
