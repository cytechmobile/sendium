package gr.cytech.sendium.core.storage.contract;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.SelectedRouterStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Common selected-work assertions; concrete fixtures supply backend composition only. */
@Timeout(10)
abstract class SelectedRouterStoreContract {
    abstract Stores createStores(int pendingCapacity, int selectedCapacity);

    @Test
    void selectionRespectsBatchLimit() throws Exception {
        try (var stores = createStores(5, 3)) {
            for (int i = 1; i <= 5; i++) {
                admit(stores, i, 2);
            }
            assertThat(stores.selected.selectToRouter(2)).isEqualTo(2);
            assertThat(stores.selected.take(Duration.ZERO)).isPresent();
            assertThat(stores.selected.take(Duration.ZERO)).isPresent();
            assertThat(stores.selected.take(Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void takenWorkRetainsWindowCapacity() throws Exception {
        try (var stores = createStores(3, 2)) {
            for (int i = 1; i <= 3; i++) {
                admit(stores, i, 2);
            }
            assertThat(stores.selected.selectToRouter(3)).isEqualTo(2);
            var taken = stores.selected.take(Duration.ZERO).orElseThrow();
            assertThat(stores.selected.selectToRouter(3)).isZero();
            stores.selected.markRouted(taken.id());
            assertThat(stores.selected.selectToRouter(3)).isEqualTo(1);
            assertThat(stores.pending.find(taken.sources().iterator().next())).isPresent();
        }
    }

    @Test
    void selectionPrefersHigherEligiblePriority() throws Exception {
        try (var stores = createStores(2, 2)) {
            SourceId low = admit(stores, 1, 1);
            SourceId high = admit(stores, 2, 3);
            assertThat(stores.selected.selectToRouter(1)).isEqualTo(1);
            var selected = stores.selected.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactly(high);
            assertThat(stores.pending.find(low)).isPresent();
        }
    }

    @Test
    void takeRetainsAcceptedSource() throws Exception {
        try (var stores = createStores(1, 1)) {
            SourceId source = admit(stores, 1, 2);
            stores.selected.selectToRouter(1);
            var selected = stores.selected.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactly(source);
            assertThat(stores.pending.find(source).orElseThrow().body).isEqualTo("accepted-1");
            assertThat(stores.selected.selectToRouter(1)).isZero();
        }
    }

    @Test
    void repeatedReleaseDoesNotDuplicateSelection() throws Exception {
        try (var stores = createStores(1, 1)) {
            admit(stores, 1, 2);
            stores.selected.selectToRouter(1);
            var first = stores.selected.take(Duration.ZERO).orElseThrow();
            stores.selected.release(first);
            stores.selected.release(first);
            assertThat(stores.selected.take(Duration.ZERO).orElseThrow().id()).isEqualTo(first.id());
            assertThat(stores.selected.take(Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void executionMutationLeavesAcceptedPayloadUnchanged() throws Exception {
        try (var stores = createStores(1, 1)) {
            SourceId source = admit(stores, 1, 2);
            stores.selected.selectToRouter(1);
            var first = stores.selected.take(Duration.ZERO).orElseThrow();
            first.message().body = "filtered";
            stores.selected.release(first);
            first.message().body = "late caller mutation";
            var returned = stores.selected.take(Duration.ZERO).orElseThrow();
            assertThat(returned.id()).isEqualTo(first.id());
            assertThat(returned.message().body).isEqualTo("filtered");
            assertThat(stores.pending.find(source).orElseThrow().body).isEqualTo("accepted-1");
        }
    }

    @Test
    void markedRoutedSourcesRemainIneligible() throws Exception {
        try (var stores = createStores(2, 1)) {
            SourceId source = admit(stores, 1, 2);
            stores.selected.selectToRouter(1);
            var taken = stores.selected.take(Duration.ZERO).orElseThrow();
            stores.selected.markRouted(taken.id());
            stores.selected.markRouted(taken.id());
            assertThat(stores.selected.selectToRouter(1)).isZero();
            assertThat(stores.selected.take(Duration.ZERO)).isEmpty();
            assertThat(stores.pending.find(source)).isPresent();
        }
    }

    @Test
    void repeatedCompletionPreservesUnrelatedSources() throws Exception {
        try (var stores = createStores(2, 1)) {
            SourceId source = admit(stores, 1, 2);
            stores.selected.selectToRouter(1);
            var taken = stores.selected.take(Duration.ZERO).orElseThrow();
            stores.selected.markRouted(taken.id());
            SourceId unrelated = admit(stores, 2, 2);
            stores.pending.complete(Set.of(source));
            stores.selected.complete(taken.id());
            stores.selected.complete(taken.id());
            assertThat(stores.pending.find(source)).isEmpty();
            assertThat(stores.pending.find(unrelated).orElseThrow().body).isEqualTo("accepted-2");
            assertThat(stores.selected.selectToRouter(1)).isEqualTo(1);
            assertThat(stores.selected.take(Duration.ZERO).orElseThrow().sources()).containsExactly(unrelated);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidLimitRetainsEligibleWork(int limit) throws Exception {
        try (var stores = createStores(1, 1)) {
            SourceId source = admit(stores, 1, 2);
            assertThatThrownBy(() -> stores.selected.selectToRouter(limit)).isInstanceOf(IllegalArgumentException.class);
            assertThat(stores.pending.find(source)).isPresent();
            assertThat(stores.selected.selectToRouter(1)).isEqualTo(1);
            assertThat(stores.selected.take(Duration.ZERO).orElseThrow().sources()).containsExactly(source);
        }
    }

    private static SourceId admit(Stores stores, int value, int priority) {
        var source = new SourceId(new UUID(0, value));
        var message = new StandardMessage();
        message.serial = source.value().toString();
        message.body = "accepted-" + value;
        message.priority = priority;
        return stores.pending.admit(source, message);
    }

    record Stores(PendingMessageStore<StandardMessage> pending,
                  SelectedRouterStore<StandardMessage> selected) implements AutoCloseable {
        Stores {
            pending.open();
            try {
                selected.open();
            } catch (RuntimeException | Error failure) {
                pending.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                selected.close();
            } finally {
                pending.close();
            }
        }
    }
}
