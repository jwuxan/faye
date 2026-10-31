package faye.identity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** The prototype's identity store; enrollments last until the server stops. */
public final class InMemoryUserStore implements UserStore {

    private final Map<String, User> bySubject = new ConcurrentHashMap<>();

    @Override
    public Optional<User> byIdentifier(String identifier) {
        return bySubject.values().stream()
                .filter(u -> u.identifier().equals(identifier))
                .findFirst();
    }

    @Override
    public Optional<User> bySubject(String subject) {
        return Optional.ofNullable(bySubject.get(subject));
    }

    @Override
    public void save(User user) {
        bySubject.put(user.subject(), user);
    }

    @Override
    public void update(String subject, UnaryOperator<User> change) {
        bySubject.computeIfPresent(subject, (s, user) -> change.apply(user));
    }
}
