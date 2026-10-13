package com.ergo.connect.documents.domain;

/** The scan itself could not be completed (unreachable, timed out, malformed protocol response). Callers must
 * treat this the same as "infected" for persistence purposes (fail closed) while surfacing a different, retryable
 * error to the caller. Public for the same reason as {@link InfectedFileException}. */
public class VirusScanException extends RuntimeException {

	VirusScanException(String message, Throwable cause) {
		super(message, cause);
	}

}
