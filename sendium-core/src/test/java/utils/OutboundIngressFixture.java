package utils;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;

import java.util.ArrayList;
import java.util.HashMap;

import static org.mockito.Mockito.spy;

public final class OutboundIngressFixture implements AutoCloseable {
    public final MemoryPendingMessageStore<StandardMessage> pending;
    public final MemorySelectedRouterStore<StandardMessage> router;
    public final MemoryRoutedWorkStore<StandardMessage> routed;
    public final DefaultOutboundCoordinator<StandardMessage> coordinator;

    public OutboundIngressFixture(int capacity) {
        pending = new MemoryPendingMessageStore<>(capacity, OutboundIngressFixture::snapshot);
        router = new MemorySelectedRouterStore<>(pending, capacity);
        routed = new MemoryRoutedWorkStore<>(capacity, OutboundIngressFixture::snapshot);
        coordinator = spy(new DefaultOutboundCoordinator<>(pending, router, routed));
        coordinator.start();
    }

    // These wire fixtures use scalar extension fields and string-valued collections, not arbitrary object graphs.
    private static StandardMessage snapshot(StandardMessage message) {
        try {
            var copy = (StandardMessage) message.clone();
            copy.attrs = message.attrs == null ? null : new HashMap<>(message.attrs);
            copy.tlvs = message.tlvs == null ? null : new HashMap<>(message.tlvs);
            copy.reassembledParts = message.reassembledParts == null ? null : new ArrayList<>(message.reassembledParts);
            return copy;
        } catch (CloneNotSupportedException failure) {
            throw new AssertionError(failure);
        }
    }

    @Override
    public void close() {
        coordinator.close();
    }
}
