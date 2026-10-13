package com.ergo.connect.documents.domain;

/** Malware was found. {@link #signature()} is the engine's name for it (e.g. {@code Eicar-Signature}), used only
 * in logs - never shown to the uploader beyond a generic rejection message. Public: {@code DocumentStore}
 * ({@code documents.api}) catches this specifically to tell "rejected" apart from {@link VirusScanException}. */
public class InfectedFileException extends RuntimeException {

	private final String signature;

	InfectedFileException(String signature) {
		super("Infected: " + signature);
		this.signature = signature;
	}

	public String signature() {
		return signature;
	}

}
