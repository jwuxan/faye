package faye.engine.spi;

/** The user store's one phone write (ADR 0032): a number the person proved they hold. */
public interface PhoneDirectory {

    /**
     * Write effect, applied after the flow state is saved: sets {@code phone} (E.164) as the
     * subject's verified number and turns phone codes on, in one write. Idempotent.
     */
    void enroll(String subject, String phone);
}
