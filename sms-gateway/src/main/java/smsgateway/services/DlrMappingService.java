package smsgateway.services;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import smsgateway.dto.DlrForwardingPayload;
import smsgateway.dto.IncomingSms;
import smsgateway.providers.LogProvider;

@ApplicationScoped
public class DlrMappingService {
    private static final Logger logger =
            LogProvider.getRoutingLogger(DlrMappingService.class.getName());

    @Inject
    @ConfigProperty(name = "sms.gateway.dlr.map.max-size", defaultValue = "100000")
    int maxMapSize;

    private Map<String, String> vendorToInternalIdMap;
    private Map<String, DlrForwardingPayload> dlrPayloadMap;

    @PostConstruct
    public void init() {
        // Create an LRU cache that evicts the oldest entry when maxSize is exceeded
        vendorToInternalIdMap =
                Collections.synchronizedMap(
                        new LinkedHashMap<String, String>(maxMapSize, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                                return size() > maxMapSize;
                            }
                        });

        dlrPayloadMap =
                Collections.synchronizedMap(
                        new LinkedHashMap<String, DlrForwardingPayload>(maxMapSize, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(
                                    Map.Entry<String, DlrForwardingPayload> eldest) {
                                return size() > maxMapSize;
                            }
                        });
    }

    public void put(String internalId, String vendorMessageId) {
        if (internalId == null || vendorMessageId == null) {
            // Or throw an IllegalArgumentException, depending on desired behavior
            return;
        }
        vendorToInternalIdMap.put(vendorMessageId, internalId);
    }

    public String getInternalId(String vendorMessageId) {
        return vendorToInternalIdMap.get(vendorMessageId);
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
