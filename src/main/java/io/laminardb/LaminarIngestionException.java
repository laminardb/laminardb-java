package io.laminardb;

/** Ingestion failures (core error codes 300–299 range 300–399), e.g. writing to a closed writer (301) or a batch/schema mismatch (302). */
public class LaminarIngestionException extends LaminarException {

    /** Constructs the exception with the core's verbatim message and numeric {@code ApiError} code. */
    public LaminarIngestionException(String message, int code) {
        super(message, code);
    }
}
