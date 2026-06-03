package com.wpanther.document.intake.domain.model;

import java.util.List;

/**
 * Value Object representing validation result
 */
public record ValidationResult(
    boolean valid,
    List<String> errors,
    List<String> warnings
) {

    public ValidationResult {
        errors = errors != null ? List.copyOf(errors) : List.of();
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    /**
     * Create a valid result
     */
    public static ValidationResult success() {
        return new ValidationResult(true, List.of(), List.of());
    }

    /**
     * Create a valid result with warnings
     */
    public static ValidationResult validWithWarnings(List<String> warnings) {
        return new ValidationResult(true, List.of(), warnings);
    }

    /**
     * Create an invalid result
     */
    public static ValidationResult invalid(List<String> errors) {
        return new ValidationResult(false, errors, List.of());
    }

    /**
     * Create an invalid result with warnings
     */
    public static ValidationResult invalid(List<String> errors, List<String> warnings) {
        return new ValidationResult(false, errors, warnings);
    }

    /**
     * Check if there are any errors
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * Check if there are any warnings
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /**
     * Get number of errors
     */
    public int errorCount() {
        return errors.size();
    }

    /**
     * Get number of warnings
     */
    public int warningCount() {
        return warnings.size();
    }
}
