package com.wpanther.document.intake.application.port.out;

import com.wpanther.document.intake.domain.model.DocumentType;
import com.wpanther.document.intake.domain.model.ValidationResult;

public interface XmlValidationPort {
    String normalize(String xmlContent);
    ValidationResult validate(String xmlContent);
    String extractDocumentNumber(String xmlContent);
    DocumentType extractDocumentType(String xmlContent);
}
