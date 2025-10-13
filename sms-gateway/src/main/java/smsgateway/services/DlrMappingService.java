package smsgateway.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import smsgateway.dto.DlrForwardingPayload;
import smsgateway.dto.IncomingSms;
import smsgateway.providers.LogProvider;

@ApplicationScoped
public class DlrMappingService {
    private static final Logger logger =
            LogProvider.getRoutingLogger(DlrMappingService.class.getName());

    private final ConcurrentHashMap<String, String> internalToVendorIdMap =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> vendorToInternalIdMap =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DlrForwardingPayload> dlrPayloadMap =
            new ConcurrentHashMap<>();

    @Inject ObjectMapper objectMapper;

    public void put(String internalId, String vendorMessageId) {
        if (internalId == null || vendorMessageId == null) {
            // Or throw an IllegalArgumentException, depending on desired behavior
            return;
        }
        internalToVendorIdMap.put(internalId, vendorMessageId);
        vendorToInternalIdMap.put(vendorMessageId, internalId);
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public String getVendorId(String internalId) {
        return internalToVendorIdMap.get(internalId);
    }

    public String getInternalId(String vendorMessageId) {
        return vendorToInternalIdMap.get(vendorMessageId);
    }

    public String removeByInternalId(String internalId) {
        String vendorId = internalToVendorIdMap.remove(internalId);
        if (vendorId != null) {
            vendorToInternalIdMap.remove(vendorId);
        }
        return vendorId;
    }

    public String removeByVendorId(String vendorMessageId) {
        String internalId = vendorToInternalIdMap.remove(vendorMessageId);
        if (internalId != null) {
            internalToVendorIdMap.remove(internalId);
        }
        return internalId;
    }

    public void storeDlrPayload(String internalId, DlrForwardingPayload payload) {
        if (internalId == null || payload == null) {
            System.out.println("Warning: internalId or payload is null. Not storing DLR payload.");
            return;
        }
        dlrPayloadMap.put(internalId, payload);
    }

    public DlrForwardingPayload getDlrPayload(String internalId) {
        return dlrPayloadMap.get(internalId);
    }

    public DlrForwardingPayload removeDlrPayload(String internalId) {
        return dlrPayloadMap.remove(internalId);
    }

    public Collection<DlrForwardingPayload> getAllDlrPayloads() {
        return this.dlrPayloadMap.values();
    }

    public void updateDlr(String respMessageId, IncomingSms msg, String status) {
        String internalId = msg.getInternalId();
        if (internalId != null) {
            put(internalId, respMessageId); // For reverse lookup
            DlrForwardingPayload payload = getDlrPayload(internalId);
            if (payload != null) {
                payload.setStatus(status); // Or an appropriate status indicating acceptance by SMSC
                payload.setSmscid(respMessageId);
                payload.setSentAt(Instant.now());

                // New additions:
                if (msg.getFrom() != null) {
                    payload.setFromAddress(msg.getFrom());
                }
                if (msg.getTo() != null) {
                    payload.setToAddress(msg.getTo());
                }
                // End of new additions

                storeDlrPayload(internalId, payload); // Update the payload
                logger.info(
                        "Worker '{}': Updated DLR status and set addresses for internalId: {}, smscid: {}",
                        msg.getGateway(),
                        internalId,
                        respMessageId);
            } else {
                logger.warn(
                        "Worker '{}': DlrForwardingPayload not found for internalId: {} when trying to set SENT status and addresses.",
                        msg.getGateway(),
                        internalId);
            }
        } else {
            logger.warn(
                    "Worker '{}': internalId is null for message from {} to {}. Cannot update DLR.",
                    msg.getGateway(),
                    msg.getFrom(),
                    msg.getTo());
        }
    }
}
