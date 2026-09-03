package edu.cornell.raven.x3a;

import java.io.IOException;

/// Signals that a `.x3a` archive or `.SUD` container is malformed — bad magic, a failed
/// header/payload CRC, or a truncated frame.
///
/// Distinct from [IllegalArgumentException] on purpose: bad *file* content is an expected
/// runtime condition callers must handle (report the file as unreadable), while bad
/// *arguments* remain a programming error.
public class X3FormatException extends IOException {

    private static final long serialVersionUID = 1L;

    /// @param message what was malformed, including the byte offset where known
    public X3FormatException(String message) {
        super(message);
    }

    /// @param cause underlying failure, typically a lower-level parse error
    public X3FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
