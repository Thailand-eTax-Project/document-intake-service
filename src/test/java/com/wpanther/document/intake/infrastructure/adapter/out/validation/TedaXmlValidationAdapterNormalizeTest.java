package com.wpanther.document.intake.infrastructure.adapter.out.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for TedaXmlValidationAdapter.normalize().
 *
 * Uses Mockito's objenesis-backed mock to bypass the constructor (which loads
 * JAXB contexts from the teda library) while still exercising the real normalize()
 * implementation. normalize() depends only on the static XML_DBF / XML_TF fields,
 * which are initialised in the static block when the class is loaded.
 */
@DisplayName("TedaXmlValidationAdapter — normalize() tests")
class TedaXmlValidationAdapterNormalizeTest {

    private TedaXmlValidationAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = mock(TedaXmlValidationAdapter.class, CALLS_REAL_METHODS);
    }

    @Test
    @DisplayName("normalize(null) throws IllegalArgumentException")
    void normalize_null_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> adapter.normalize(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("xmlContent must not be null");
    }

    @Test
    @DisplayName("normalize(blank) returns blank string unchanged")
    void normalize_blank_returnsUnchanged() {
        assertThat(adapter.normalize("")).isEqualTo("");
        assertThat(adapter.normalize("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("normalize already-compact XML is returned as a single line")
    void normalize_compactXml_returnsCompact() {
        String compact = "<root><child>value</child></root>";
        String result = adapter.normalize(compact);
        assertThat(result).doesNotContainPattern(">\\s+<");
        assertThat(result).contains("<child>value</child>");
    }

    @Test
    @DisplayName("normalize indented XML strips inter-element whitespace and preserves element content")
    void normalize_indentedXml_stripsWhitespacePreservesContent() {
        String indented = """
            <root>
                <child>value</child>
                <nested>
                    <item>42</item>
                </nested>
            </root>
            """;

        String result = adapter.normalize(indented);

        assertThat(result).doesNotContainPattern(">\\s+<");
        assertThat(result).doesNotContain("\n");
        assertThat(result).contains("<child>value</child>");
        assertThat(result).contains("<item>42</item>");
    }

    @Test
    @DisplayName("normalize preserves text content inside tags (amounts, IDs, dates)")
    void normalize_preservesTextContent() {
        String xml = "<invoice><amount>  1000.50  </amount><id>INV-001</id></invoice>";
        String result = adapter.normalize(xml);
        assertThat(result).contains("<amount>  1000.50  </amount>");
        assertThat(result).contains("<id>INV-001</id>");
    }

    @Test
    @DisplayName("normalize malformed XML throws IllegalArgumentException")
    void normalize_malformedXml_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> adapter.normalize("<unclosed>"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("XML normalization failed");
    }
}
