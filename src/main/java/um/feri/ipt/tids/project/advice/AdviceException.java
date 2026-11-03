package um.feri.ipt.tids.project.advice;

/** Only safe public messages cross the HTTP boundary; no provider payloads or credentials. */
public final class AdviceException extends RuntimeException {
    private final int status;
    private final String code;
    public AdviceException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
