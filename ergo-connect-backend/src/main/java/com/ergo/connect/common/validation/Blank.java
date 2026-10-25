package com.ergo.connect.common.validation;

/**
 * "Blank" as a person sees it: also true for no-break and zero-width spaces and other format characters, which
 * {@code String.isBlank()} and {@code @NotBlank} let through. One rule for every reason, comment and note.
 */
public final class Blank {

	private Blank() {
	}

	public static boolean is(String value) {
		return value == null || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
				|| Character.getType(c) == Character.FORMAT);
	}

}
