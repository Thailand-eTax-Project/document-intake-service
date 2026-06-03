package com.wpanther.document.intake.domain.exception;

public class DuplicateDocumentException extends RuntimeException {

    private final String documentNumber;

    private static final String MESSAGE_SUFFIX =
        ". A document with this number has already been submitted. " +
        "Please check existing documents or use a different document number.";

    public DuplicateDocumentException(String documentNumber) {
        super("Document number already exists: " + documentNumber + MESSAGE_SUFFIX);
        this.documentNumber = documentNumber;
    }

    public DuplicateDocumentException(String documentNumber, Throwable cause) {
        super("Document number already exists: " + documentNumber + MESSAGE_SUFFIX, cause);
        this.documentNumber = documentNumber;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }
}
