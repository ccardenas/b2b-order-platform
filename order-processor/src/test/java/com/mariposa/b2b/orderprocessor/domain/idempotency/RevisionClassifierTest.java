package com.mariposa.b2b.orderprocessor.domain.idempotency;

import com.mariposa.b2b.orderprocessor.domain.model.OrderStatus;
import com.mariposa.b2b.orderprocessor.domain.model.StoredRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RevisionClassifierTest {

    private final RevisionClassifier classifier = new RevisionClassifier();

    @Test
    void anOrderThatIsNotStoredYetIsNewer() {
        assertThat(classifier.classify(Optional.empty(), 1, "E-1")).isEqualTo(RevisionComparison.NEWER);
    }

    @ParameterizedTest(name = "stored v{0} {2} vs incoming v{1} {3} -> {4}")
    @CsvSource({
            "1, 2, E-1, E-2, NEWER",
            "1, 2, E-1, E-1, NEWER",
            "2, 1, E-2, E-1, STALE",
            "2, 1, E-2, E-2, STALE",
            "1, 1, E-1, E-1, DUPLICATE",
            "1, 1, E-1, E-2, VERSION_CONFLICT"
    })
    void classifiesApprovedOrRejectedRevisions(int storedVersion, int incomingVersion, String storedEvent, String incomingEvent,
                                               RevisionComparison expected) {
        StoredRevision stored = new StoredRevision(storedVersion, storedEvent, OrderStatus.APPROVED);

        assertThat(classifier.classify(Optional.of(stored), incomingVersion, incomingEvent)).isEqualTo(expected);
    }

    @Test
    void theSameEventOverATechnicalFailureIsARetry() {
        StoredRevision stored = new StoredRevision(1, "E-1", OrderStatus.TECHNICAL_FAILURE);

        assertThat(classifier.classify(Optional.of(stored), 1, "E-1")).isEqualTo(RevisionComparison.RETRY_OF_TECHNICAL_FAILURE);
    }

    @Test
    void aDifferentEventOverATechnicalFailureOfTheSameVersionIsStillAConflict() {
        StoredRevision stored = new StoredRevision(1, "E-1", OrderStatus.TECHNICAL_FAILURE);

        assertThat(classifier.classify(Optional.of(stored), 1, "E-2")).isEqualTo(RevisionComparison.VERSION_CONFLICT);
    }

    @Test
    void onlyNewerAndRetriesAreWritable() {
        assertThat(RevisionComparison.values()).filteredOn(RevisionComparison::isWritable)
                .containsExactly(RevisionComparison.NEWER, RevisionComparison.RETRY_OF_TECHNICAL_FAILURE);
    }
}
