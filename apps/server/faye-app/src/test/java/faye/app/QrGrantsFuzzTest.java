package faye.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import faye.engine.spi.QrGrants;
import faye.engine.spi.QrGrants.Grant;
import faye.engine.spi.QrGrants.Status;
import faye.engine.testing.InMemoryQrGrants;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR 0032, ADR 0007: the QR code grant store under any sequence of creates, approvals, refusals
 * and consumptions behaves like the in memory model: a grant is approved only while pending and
 * unexpired, for one subject; consumed only once approved, unexpired and by its own flow; and a
 * refusal or a consumption is final.
 */
@SpringBootTest
class QrGrantsFuzzTest {

    static final List<String> FLOWS = List.of("f_a", "f_b");
    static final List<String> SUBJECTS = List.of("alice", "bob");
    static final Clock EPOCH = Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);

    @Autowired
    JdbcClient db;

    @FuzzTest(maxDuration = "60s")
    void grantStore(FuzzedDataProvider data) {
        db.sql("DELETE FROM faye_qr_grant").update();
        // At the epoch, so the store's sweep of long expired grants never runs: the model has none
        QrGrants sqlite = new SqliteQrGrants(db, EPOCH);
        QrGrants model = new InMemoryQrGrants();
        int ops = data.consumeInt(1, 30);
        for (int i = 0; i < ops && data.remainingBytes() > 0; i++) {
            String hash = "h" + data.consumeInt(0, 2);
            long now = data.consumeLong(0, 10);
            switch (data.consumeInt(0, 4)) {
                case 0 -> {
                    if (model.find(hash).isPresent()) break;
                    Grant g = new Grant(
                            hash,
                            data.pickValue(FLOWS),
                            "acme",
                            data.consumeBoolean(),
                            Map.of("client_id", "web"),
                            data.consumeInt(10, 99),
                            "",
                            data.consumeLong(0, 10),
                            Status.PENDING,
                            null,
                            List.of());
                    sqlite.create(g);
                    model.create(g);
                }
                case 1 -> {
                    String subject = data.pickValue(SUBJECTS);
                    List<String> amr = data.consumeBoolean() ? List.of("pwd") : List.of("hwk", "user");
                    assertThat(sqlite.approve(hash, subject, amr, now))
                            .as("approve")
                            .isEqualTo(model.approve(hash, subject, amr, now));
                }
                case 2 -> {
                    sqlite.refuse(hash);
                    model.refuse(hash);
                }
                case 3 -> {
                    String flow = data.pickValue(FLOWS);
                    Optional<Grant> got = sqlite.consume(hash, flow, now);
                    Optional<Grant> want = model.consume(hash, flow, now);
                    assertThat(got.isPresent()).as("consume").isEqualTo(want.isPresent());
                    got.ifPresent(g -> {
                        assertThat(g.flowId()).isEqualTo(flow);
                        assertThat(g.subject()).isNotNull();
                    });
                }
                default -> {}
            }
            assertThat(sqlite.find(hash).map(Grant::status))
                    .isEqualTo(model.find(hash).map(Grant::status));
            assertThat(sqlite.find(hash).map(Grant::subject))
                    .isEqualTo(model.find(hash).map(Grant::subject));
        }
    }

    /**
     * Found by the harness: creating a grant sweeps out those that expired a day before, and only
     * those, so a grant still within its day is found.
     */
    @Test
    void aSweepDeletesOnlyGrantsExpiredADayAgo() {
        db.sql("DELETE FROM faye_qr_grant").update();
        long day = java.time.Duration.ofDays(1).toMillis();
        QrGrants grants =
                new SqliteQrGrants(db, Clock.fixed(java.time.Instant.ofEpochMilli(2 * day), java.time.ZoneOffset.UTC));
        grants.create(
                new Grant("old", "f_a", "acme", false, Map.of(), 42, "", day - 1, Status.PENDING, null, List.of()));
        grants.create(
                new Grant("recent", "f_a", "acme", false, Map.of(), 42, "", day + 1, Status.PENDING, null, List.of()));
        grants.create(
                new Grant("new", "f_a", "acme", false, Map.of(), 42, "", 3 * day, Status.PENDING, null, List.of()));
        assertThat(grants.find("old")).isEmpty();
        assertThat(grants.find("recent")).isPresent();
        assertThat(grants.find("new")).isPresent();
    }

    /** One readable case of what the harness checks: an approval is final, a consumption once per flow. */
    @Test
    void approvedOnceConsumedByItsFlowOnly() {
        db.sql("DELETE FROM faye_qr_grant").update();
        QrGrants grants = new SqliteQrGrants(db, Clock.systemUTC());
        grants.create(new Grant("h", "f_a", "acme", false, Map.of(), 42, "", 100, Status.PENDING, null, List.of()));
        assertThat(grants.consume("h", "f_a", 1)).isEmpty();
        assertThat(grants.approve("h", "alice", List.of("pwd"), 1)).isTrue();
        assertThat(grants.approve("h", "bob", List.of("pwd"), 1)).isFalse();
        grants.refuse("h");
        assertThat(grants.consume("h", "f_b", 1)).isEmpty();
        assertThat(grants.consume("h", "f_a", 1))
                .hasValueSatisfying(g -> assertThat(g.amr()).containsExactly("pwd"));
        assertThat(grants.consume("h", "f_a", 1)).isPresent();
        assertThat(grants.consume("h", "f_a", 100)).isEmpty();
    }
}
