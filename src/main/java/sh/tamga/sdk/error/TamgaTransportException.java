package sh.tamga.sdk.error;

/**
 * Thrown when a request never produced an HTTP response -- a connection failure, a timeout, a TLS
 * error, or an interrupted retry backoff.
 *
 * <p>Distinct from {@link TamgaApiException}, which always carries a real server response. A caller
 * deciding whether to fall back to offline verification wants this distinction: a transport failure
 * says nothing about the license, whereas an API error does.
 */
public final class TamgaTransportException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates a transport exception with a message and the underlying cause. */
  public TamgaTransportException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Creates a transport exception with a message only. */
  public TamgaTransportException(String message) {
    super(message);
  }
}
