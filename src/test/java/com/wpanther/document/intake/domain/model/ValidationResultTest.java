package com.wpanther.document.intake.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ValidationResult record
 */
class ValidationResultTest {

    @Test
    void testSuccessCreatesValidResultWithNoErrorsOrWarnings() {
        ValidationResult result = ValidationResult.success();

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.hasWarnings()).isFalse();
        assertThat(result.errorCount()).isZero();
        assertThat(result.warningCount()).isZero();
    }

    @Test
    void testValidWithWarningsCreatesValidResultWithWarnings() {
        List<String> warnings = List.of("Warning 1", "Warning 2");
        ValidationResult result = ValidationResult.validWithWarnings(warnings);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).containsExactlyElementsOf(warnings);
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.errorCount()).isZero();
        assertThat(result.warningCount()).isEqualTo(2);
    }

    @Test
    void testInvalidCreatesInvalidResultWithErrors() {
        List<String> errors = List.of("Error 1", "Error 2", "Error 3");
        ValidationResult result = ValidationResult.invalid(errors);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactlyElementsOf(errors);
        assertThat(result.warnings()).isEmpty();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.hasWarnings()).isFalse();
        assertThat(result.errorCount()).isEqualTo(3);
        assertThat(result.warningCount()).isZero();
    }

    @Test
    void testInvalidWithErrorsAndWarningsCreatesInvalidResult() {
        List<String> errors = List.of("Error 1");
        List<String> warnings = List.of("Warning 1", "Warning 2");
        ValidationResult result = ValidationResult.invalid(errors, warnings);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactlyElementsOf(errors);
        assertThat(result.warnings()).containsExactlyElementsOf(warnings);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.errorCount()).isEqualTo(1);
        assertThat(result.warningCount()).isEqualTo(2);
    }

    @Test
    void testConstructorDefensivelyCopiesErrors() {
        List<String> mutableErrors = new java.util.ArrayList<>(List.of("Error 1"));
        ValidationResult result = new ValidationResult(false, mutableErrors, List.of());

        mutableErrors.add("Error 2");

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors()).containsExactly("Error 1");
    }

    @Test
    void testConstructorDefensivelyCopiesWarnings() {
        List<String> mutableWarnings = new java.util.ArrayList<>(List.of("Warning 1"));
        ValidationResult result = new ValidationResult(false, List.of(), mutableWarnings);

        mutableWarnings.add("Warning 2");

        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings()).containsExactly("Warning 1");
    }

    @Test
    void testConstructorWithNullErrorsDefaultsToEmptyList() {
        ValidationResult result = new ValidationResult(true, null, List.of());

        assertThat(result.errors()).isNotNull();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void testConstructorWithNullWarningsDefaultsToEmptyList() {
        ValidationResult result = new ValidationResult(true, List.of(), null);

        assertThat(result.warnings()).isNotNull();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void testHasErrorsReturnsTrueWhenErrorsPresent() {
        ValidationResult result = ValidationResult.invalid(List.of("Error 1"));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void testHasErrorsReturnsFalseWhenNoErrors() {
        ValidationResult result = ValidationResult.success();

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void testHasWarningsReturnsTrueWhenWarningsPresent() {
        ValidationResult result = ValidationResult.validWithWarnings(List.of("Warning 1"));

        assertThat(result.hasWarnings()).isTrue();
    }

    @Test
    void testHasWarningsReturnsFalseWhenNoWarnings() {
        ValidationResult result = ValidationResult.success();

        assertThat(result.hasWarnings()).isFalse();
    }

    @Test
    void testErrorCountReturnsCorrectCount() {
        ValidationResult result = ValidationResult.invalid(List.of("E1", "E2", "E3"));

        assertThat(result.errorCount()).isEqualTo(3);
    }

    @Test
    void testWarningCountReturnsCorrectCount() {
        ValidationResult result = ValidationResult.validWithWarnings(List.of("W1", "W2"));

        assertThat(result.warningCount()).isEqualTo(2);
    }

    @Test
    void testEmptyErrorsListInInvalid() {
        ValidationResult result = ValidationResult.invalid(List.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isEmpty();
        assertThat(result.errorCount()).isZero();
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void testEmptyWarningsListInValidWithWarnings() {
        ValidationResult result = ValidationResult.validWithWarnings(List.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.warningCount()).isZero();
        assertThat(result.hasWarnings()).isFalse();
    }

    @Test
    void testValidWithErrorsOnly() {
        ValidationResult result = ValidationResult.invalid(List.of("Error"));

        assertThat(result.valid()).isFalse();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.hasWarnings()).isFalse();
    }

    @Test
    void testValidWithWarningsOnly() {
        ValidationResult result = ValidationResult.validWithWarnings(List.of("Warning"));

        assertThat(result.valid()).isTrue();
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.hasWarnings()).isTrue();
    }

    @Test
    void testValidWithBothErrorsAndWarnings() {
        ValidationResult result = ValidationResult.invalid(
            List.of("Error 1", "Error 2"),
            List.of("Warning 1")
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.errorCount()).isEqualTo(2);
        assertThat(result.warningCount()).isEqualTo(1);
    }

    @Test
    void testRecordEqualsAndHashCode() {
        ValidationResult result1 = ValidationResult.invalid(List.of("Error"), List.of("Warning"));
        ValidationResult result2 = ValidationResult.invalid(List.of("Error"), List.of("Warning"));
        ValidationResult result3 = ValidationResult.invalid(List.of("Error"), List.of());

        assertThat(result1).isEqualTo(result2);
        assertThat(result1).hasSameHashCodeAs(result2);
        assertThat(result1).isNotEqualTo(result3);
    }

    @Test
    void testToStringContainsValidField() {
        ValidationResult result = ValidationResult.success();
        String string = result.toString();

        assertThat(string).contains("valid=true");
    }

    @Test
    void testFactoryMethodsReturnImmutableLists() {
        ValidationResult result = ValidationResult.invalid(List.of("Error"));

        assertThatThrownBy(() -> result.errors().add("Another error"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
