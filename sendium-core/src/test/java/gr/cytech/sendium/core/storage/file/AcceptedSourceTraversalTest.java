package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class AcceptedSourceTraversalTest {
    @TempDir Path accepted;

    @BeforeEach
    void priorityRoots() throws IOException {
        for (int priority : List.of(3, 2, 1)) {
            Files.createDirectory(accepted.resolve(Integer.toString(priority)));
        }
    }

    @Test
    void checksOnlyTheThreeSupportedPrioritiesInDescendingOrder() throws Exception {
        SourceId low = publish(1, 0, 1);
        SourceId normal = publish(2, 0, 2);
        SourceId high = publish(3, 255, 3);
        try (var traversal = new AcceptedSourceTraversal(accepted)) {
            var page = traversal.poll(3, 2048, source -> true);
            assertThat(page.candidates()).extracting(AcceptedSourceTraversal.Candidate::source).containsExactly(high, normal, low);
            assertThat(page.candidates()).extracting(AcceptedSourceTraversal.Candidate::priority).containsExactly(3, 2, 1);
        }
    }

    @Test
    void budgetsEmptyBucketProbesAndDoesNotMistakeAPartialScanForAnEmptySpool() throws Exception {
        try (var traversal = new AcceptedSourceTraversal(accepted)) {
            int steps = 0;
            boolean complete = false;
            for (int polls = 0; polls < 100 && !complete; polls++) {
                var page = traversal.poll(5, 17, source -> true);
                assertThat(page.candidates()).isEmpty();
                assertThat(page.steps()).isBetween(0, 17);
                steps += page.steps();
                complete = page.roundComplete();
            }
            assertThat(complete).isTrue();
            assertThat(steps).isEqualTo(3 * 256);
        }
    }

    @Test
    void boundedPagesStreamALargeBucketWithoutReadingPayloadsOrRememberingAllIds() throws Exception {
        Set<SourceId> expected = new HashSet<>();
        for (int index = 0; index < 1000; index++) {
            expected.add(publish(3, 42, index));
        }
        Set<SourceId> active = new HashSet<>(); // Caller-owned reservations, not traversal state.
        try (var traversal = new AcceptedSourceTraversal(accepted)) {
            boolean complete = false;
            int steps = 0;
            for (int polls = 0; polls < 300 && !complete; polls++) {
                var page = traversal.poll(17, 23, source -> !active.contains(source));
                assertThat(page.candidates()).hasSizeLessThanOrEqualTo(17);
                assertThat(page.steps()).isBetween(0, 23);
                steps += page.steps();
                for (var candidate : page.candidates()) {
                    assertThat(active.add(candidate.source())).isTrue();
                }
                complete = page.roundComplete();
            }
            assertThat(complete).isTrue();
            assertThat(active).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(steps).isEqualTo(3 * 256 + expected.size());
        }
    }

    @Test
    void continuingAPassReachesOlderWorkDespiteNewArrivalsInEarlierBuckets() throws Exception {
        SourceId older = publish(3, 255, 1);
        Set<SourceId> active = new HashSet<>();
        try (var traversal = new AcceptedSourceTraversal(accepted)) {
            for (int poll = 0; poll < 100 && !active.contains(older); poll++) {
                publish(3, 0, poll + 100);
                var page = traversal.poll(1, 16, source -> !active.contains(source));
                page.candidates().forEach(candidate -> active.add(candidate.source()));
            }
            assertThat(active).contains(older);
        }
    }

    @Test
    void laterBatchesCheckHigherPriorityBeforeContinuingLowerPriorityWork() throws Exception {
        publish(2, 0, 1);
        try (var traversal = new AcceptedSourceTraversal(accepted)) {
            var initial = traversal.poll(1, 1024, source -> true);
            assertThat(initial.candidates()).singleElement().satisfies(candidate -> assertThat(candidate.priority()).isEqualTo(2));
            SourceId higher = publish(3, 0, 2);
            var next = traversal.poll(1, 1024, source -> true);
            assertThat(next.candidates()).singleElement().satisfies(candidate -> assertThat(candidate.source()).isEqualTo(higher));
        }
    }

    @Test
    void removedBufferedEntriesAreSkippedAndAdmissionsBehindTheCursorAppearOnAnotherPass() throws Exception {
        List<SourceId> initial = List.of(publish(3, 0, 1), publish(3, 0, 2), publish(3, 0, 3));
        Set<SourceId> active = new HashSet<>();
        try (var traversal = new AcceptedSourceTraversal(accepted)) {
            var page = traversal.poll(1, 8, source -> true);
            assertThat(page.candidates()).hasSize(1);
            active.add(page.candidates().getFirst().source());
            for (SourceId source : initial) {
                Files.delete(path(3, source));
            }
            SourceId later = publish(3, 0, 4);
            for (int poll = 0; poll < 100 && !active.contains(later); poll++) {
                traversal.poll(2, 32, source -> !active.contains(source)).candidates()
                        .forEach(candidate -> active.add(candidate.source()));
            }
            assertThat(active).hasSize(2).contains(later);
        }
    }

    @Test
    void callerOwnedActiveSetPreventsReselectionWithoutRemovingAcceptedFiles() throws Exception {
        SourceId source = publish(3, 0, 1);
        Set<SourceId> active = new HashSet<>();
        try (var traversal = new AcceptedSourceTraversal(accepted)) {
            var page = traversal.poll(1, 1024, candidate -> !active.contains(candidate));
            active.add(page.candidates().getFirst().source());
            assertThat(traversal.poll(10, 2048, candidate -> !active.contains(candidate)).candidates()).isEmpty();
            assertThat(traversal.poll(10, 2048, candidate -> !active.contains(candidate)).candidates()).isEmpty();
            assertThat(path(3, source)).isRegularFile();
            active.clear(); // A failed reservation can become eligible again; no disk marker needs changing.
            assertThat(traversal.poll(10, 2048, candidate -> !active.contains(candidate)).candidates())
                    .extracting(AcceptedSourceTraversal.Candidate::source).containsExactly(source);
        }
    }

    @Test
    void invalidEntriesFailClosedAndCloseTheTraversal() throws Exception {
        Path bucket = Files.createDirectory(accepted.resolve("3/00"));
        Files.writeString(bucket.resolve("unexpected"), "not a source");
        var traversal = new AcceptedSourceTraversal(accepted);
        assertThatThrownBy(() -> traversal.poll(1, 16, source -> true)).isInstanceOf(IOException.class)
                .hasMessageContaining("reason:invalidSourceEntry");
        assertThatThrownBy(() -> traversal.poll(1, 16, source -> true)).hasMessageContaining("reason:invalidPollState");
        traversal.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"01000000-0000-4000-8000-000000000001", "00ABCDEF-0000-4000-8000-000000000001", "00-0-0-0-1", ""})
    void wrongBucketAndNonCanonicalUuidNamesAreRejected(String id) throws Exception {
        Path bucket = Files.createDirectory(accepted.resolve("3/00"));
        Files.writeString(bucket.resolve(id + ".cbor"), "fixture");
        try (var traversal = new AcceptedSourceTraversal(accepted)) {
            assertThatThrownBy(() -> traversal.poll(1, 16, source -> true)).hasMessageContaining("reason:invalidSourceName");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void filesCannotSubstituteForPriorityOrBucketDirectories(boolean priorityRoot) throws Exception {
        Path path = accepted.resolve(priorityRoot ? "3" : "3/00");
        if (priorityRoot) {
            Files.delete(path);
        }
        Files.writeString(path, "not a directory");
        try (var traversal = new AcceptedSourceTraversal(accepted)) {
            assertThatThrownBy(() -> traversal.poll(1, 16, source -> true)).isInstanceOf(IOException.class)
                    .hasMessageContaining(priorityRoot ? "invalidPriorityDirectory" : "invalidBucketDirectory");
        }
    }

    @Test
    void missingPriorityRootDoesNotReportAnEmptySpool() throws Exception {
        Files.delete(accepted.resolve("3"));
        try (var traversal = new AcceptedSourceTraversal(accepted)) {
            assertThatThrownBy(() -> traversal.poll(1, 16, source -> true)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void closeAndInvalidBudgetsDoNotStartAnotherTraversal() throws Exception {
        var traversal = new AcceptedSourceTraversal(accepted);
        assertThatThrownBy(() -> traversal.poll(0, 16, source -> true)).hasMessageContaining("invalidPollState");
        assertThatThrownBy(() -> traversal.poll(1, 0, source -> true)).hasMessageContaining("invalidPollState");
        traversal.close();
        assertThatThrownBy(() -> traversal.poll(1, 16, source -> true)).hasMessageContaining("invalidPollState");
    }

    private SourceId publish(int priority, int bucket, long number) throws IOException {
        UUID id = UUID.fromString("%02x000000-0000-4000-8000-%012x".formatted(bucket, number));
        SourceId source = new SourceId(id);
        Path path = path(priority, source);
        Files.createDirectories(path.getParent());
        // Deliberately invalid CBOR: the cursor must identify candidates without loading/decoding bodies.
        Files.writeString(path, "payload must not be read by traversal");
        return source;
    }

    private Path path(int priority, SourceId source) {
        String id = source.value().toString();
        return accepted.resolve(Integer.toString(priority)).resolve(id.substring(0, 2)).resolve(id + ".cbor");
    }
}
