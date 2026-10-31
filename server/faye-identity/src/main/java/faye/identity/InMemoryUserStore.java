package faye.identity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The prototype's identity store; enrollments last until the server stops. */
public final class InMemoryUserStore implements UserStore {

    private final Map<String, User> bySubject = new HashMap<>();
    /** The flow that used each subject's last TOTP step. */
    private final Map<String, String> totpLastFlow = new HashMap<>();

    @Override
    public synchronized Optional<User> byIdentifier(String identifier) {
        return bySubject.values().stream()
                .filter(u -> u.identifier().equals(identifier))
                .findFirst();
    }

    @Override
    public synchronized Optional<User> bySubject(String subject) {
        return Optional.ofNullable(bySubject.get(subject));
    }

    @Override
    public synchronized List<User> all() {
        return bySubject.values().stream()
                .sorted(Comparator.comparing(User::identifier))
                .toList();
    }

    @Override
    public synchronized void save(User user) {
        bySubject.put(user.subject(), user);
    }

    @Override
    public synchronized boolean useTotpStep(String subject, long step, String flowId) {
        User u = bySubject.get(subject);
        if (u == null || u.totpSecret() == null) return false;
        boolean fresh =
                step > u.totpLastStep() || (step == u.totpLastStep() && flowId.equals(totpLastFlow.get(subject)));
        if (!fresh) return false;
        bySubject.put(subject, u.withTotp(u.totpSecret(), step));
        totpLastFlow.put(subject, flowId);
        return true;
    }

    @Override
    public synchronized void enrollTotp(String subject, String secret, long step, String flowId) {
        User u = bySubject.get(subject);
        if (u == null) return;
        bySubject.put(subject, u.withTotp(secret, step));
        totpLastFlow.put(subject, flowId);
    }

    @Override
    public synchronized boolean addPasskey(String subject, User.Passkey passkey) {
        User u = bySubject.get(subject);
        if (u == null) return false;
        for (User other : bySubject.values()) {
            for (User.Passkey p : other.passkeys()) {
                if (p.credentialId().equals(passkey.credentialId())) {
                    return other.subject().equals(subject);
                }
            }
        }
        List<User.Passkey> all = new ArrayList<>(u.passkeys());
        all.add(passkey);
        bySubject.put(subject, u.withPasskeys(all));
        return true;
    }

    @Override
    public synchronized void raiseSignCount(String subject, String credentialId, long count) {
        User u = bySubject.get(subject);
        if (u == null) return;
        bySubject.put(
                subject,
                u.withPasskeys(u.passkeys().stream()
                        .map(p -> Objects.equals(p.credentialId(), credentialId) && count > p.signCount()
                                ? new User.Passkey(p.credentialId(), p.attestedCredentialData(), count)
                                : p)
                        .toList()));
    }
}
