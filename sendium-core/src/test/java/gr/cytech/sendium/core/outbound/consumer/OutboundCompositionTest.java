package gr.cytech.sendium.core.outbound.consumer;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.SelectedRouterStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboundCompositionTest {
    @Test
    @SuppressWarnings("unchecked")
    void externalConsumerUsesCustomMessageTypesThroughTheThreeStagePortsDirectly() throws Exception {
        PendingMessageStore<CampaignMessage> pending = mock(PendingMessageStore.class);
        SelectedRouterStore<CampaignMessage> router = mock(SelectedRouterStore.class);
        RoutedWorkStore<CampaignMessage> routed = mock(RoutedWorkStore.class);

        var message = new CampaignMessage();
        message.campaignId = "campaign-7";
        var selection = new OutboundWork.SelectionId(UUID.randomUUID());
        var source = new OutboundWork.SourceId(UUID.randomUUID());
        var sources = Set.of(source);
        var selected = new OutboundWork.Selected<>(selection, sources, message);
        var destination = new OutboundWork.Destination<>("custom-provider", selected.message());
        var assignment = new OutboundWork.Assignment<>(selection, sources, destination);
        var work = new OutboundWork.Routed<>(new OutboundWork.WorkId(UUID.randomUUID()),
                selection, destination.name(), destination.message());
        when(router.take(Duration.ZERO)).thenReturn(Optional.of(selected));
        when(routed.record(assignment)).thenReturn(work);

        pending.admit(source, message);
        CampaignMessage selectedMessage = router.take(Duration.ZERO).orElseThrow().message();
        CampaignMessage routedMessage = routed.record(assignment).message();

        assertThat(selectedMessage.campaignId).isEqualTo("campaign-7");
        assertThat(routedMessage.campaignId).isEqualTo("campaign-7");
        verify(pending).admit(source, message);
    }

    private static final class CampaignMessage extends StandardMessage {
        private String campaignId;
    }
}
