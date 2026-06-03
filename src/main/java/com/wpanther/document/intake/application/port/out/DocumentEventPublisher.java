package com.wpanther.document.intake.application.port.out;

import com.wpanther.document.intake.domain.model.IncomingDocument;

public interface DocumentEventPublisher {
    void publishStartSagaCommand(IncomingDocument document, String xmlContent);
    void publishTraceEvent(IncomingDocument document);
}
