package smsgateway.services;

import static smsgateway.smpp.SmppClientWorker.getSourceAddress;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.type.Address;
import com.google.common.base.Strings;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import smsgateway.dto.DlrForwardingPayload;
import smsgateway.providers.LogProvider;
import smsgateway.smpp.server.SmppServerHandler;

@ApplicationScoped
public class DlrForwardingService {
    private static final Logger logger =
            LogProvider.getRoutingLogger(DlrForwardingService.class.getName());
    private static final DateTimeFormatter DLR_DATE_FORMATTER =
            DateTimeFormat.forPattern("yyMMddHHmm");

    @Inject Vertx vertx;

    private WebClient webClient;

    @PostConstruct
    void initialize() {
        this.webClient = WebClient.create(vertx, new WebClientOptions());
    }

    @Inject DlrMappingService dlrMappingService;

    @Inject ManagedExecutor asyncExecutor;

    @Inject SmppServerHandler smppServerHandler;

    @ConfigProperty(name = "sms.gateway.dlr.forwarding.enabled")
    public boolean forwardingEnabled;

    @ConfigProperty(name = "quarkus.rest-client.dlr-forwarding.url")
    String forwardingUrl;

    public void forwardDlr(DlrForwardingPayload payload, String vendorId) {
        if (!forwardingEnabled) {
            logger.warn(
                    "DLR forwarding is disabled. Skipping for internalId: {}, smscid: {}, vendorId: {}",
                    payload.getForwardingId(),
                    payload.getSmscid(),
                    vendorId);
            return;
        }

        if (payload == null || payload.getForwardingId() == null) {
            logger.warn(
                    "Received null payload or payload with null forwardingId for vendor: {}. Skipping DLR forwarding.",
                    vendorId);
            return;
        }

        logger.info(
                "Forwarding DLR for internalMessageId: {}, (smscid: {}, vendorId: {})",
                payload.getForwardingId(),
                payload.getSmscid(),
                vendorId);

        // The payload is now directly passed and should be already populated.
        // No need to create and set fields here.
        if (payload.getOriginatingSessionId() != null) {
            var session =
                    smppServerHandler
                            .getActiveSessionService()
                            .getSession(payload.getOriginatingSessionId());
            if (session.isPresent()) {
                sendDeliveryReceiptAsync(session.get(), payload);
            } else {
                logger.warn("Session not found for dlr:{}", payload);
            }
        } else {
            String url =
                    Strings.isNullOrEmpty(payload.getForwardUrl())
                            ? forwardingUrl
                            : payload.getForwardUrl();
            webClient
                    .postAbs(url)
                    .putHeader("Content-Type", "application/json")
                    .sendJsonObject(JsonObject.mapFrom(payload))
                    .subscribe()
                    .with(
                            response -> {
                                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                                    logger.info(
                                            "Successfully forwarded DLR for internalMessageId: {} (smscid: {}) to URL: {}",
                                            payload.getForwardingId(),
                                            payload.getSmscid(),
                                            forwardingUrl);
                                } else {
                                    logger.error(
                                            "Failed to forward DLR for internalMessageId: {} (smscid: {}) to URL: {}. Status code: {}, Body: {}",
                                            payload.getForwardingId(),
                                            payload.getSmscid(),
                                            forwardingUrl,
                                            response.statusCode(),
                                            response.bodyAsString());
                                }
                            },
                            failure ->
                                    logger.error(
                                            "Failed to forward DLR for internalMessageId: {} (smscid: {}) to URL: {}. Error: {}",
                                            payload.getForwardingId(),
                                            payload.getSmscid(),
                                            forwardingUrl,
                                            failure.getMessage(),
                                            failure));
        }
        var dlr = dlrMappingService.getDlrPayload(payload.getForwardingId());
        // It's possible that dlr is null if the mapping wasn't created or was removed.
        if (dlr != null) {
            dlr.setForwardDate(Instant.now());
            dlrMappingService.storeDlrPayload(payload.getForwardingId(), dlr);
        } else {
            // Log a warning if the DLR payload is not found, as this might indicate an issue.
            logger.warn(
                    "DLR payload not found for forwardingId: {}. Cannot update forwardDate.",
                    payload.getForwardingId());
        }
    }

    private void sendDeliveryReceiptAsync(SmppSession clientSession, DlrForwardingPayload payload) {
        asyncExecutor.execute(
                () -> {
                    try {
                        DeliverSm dlr = new DeliverSm();
                        dlr.setEsmClass(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
                        Address srcAddr = getSourceAddress(payload.getFromAddress());
                        Address dstAddr =
                                new Address(
                                        SmppConstants.TON_INTERNATIONAL,
                                        SmppConstants.NPI_UNKNOWN,
                                        payload.getToAddress());

                        dlr.setSourceAddress(dstAddr);
                        dlr.setDestAddress(srcAddr);

                        DateTime currentTime = new DateTime(DateTimeZone.UTC);
                        String submitDate = currentTime.toString(DLR_DATE_FORMATTER);
                        String doneDate = submitDate;
                        var shortMessageText = payload.getBody();
                        if (shortMessageText.length() > 20) {
                            shortMessageText = shortMessageText.substring(0, 20);
                        }

                        String dlrText =
                                String.format(
                                        "id:%s sub:001 dlvrd:001 submit date:%s done date:%s stat:%s err:%s text:%s",
                                        payload.getForwardingId(),
                                        submitDate,
                                        doneDate,
                                        payload.getStatus(),
                                        payload.getErrorCode(),
                                        shortMessageText);

                        dlr.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
                        dlr.setShortMessage(
                                CharsetUtil.encode(
                                        dlrText,
                                        CharsetUtil.CHARSET_GSM)); // Use CharsetUtil.encode

                        clientSession.sendRequestPdu(dlr, 30000, false);
                        logger.info(
                                "Sending DLR for Message ID: {} to {}. DLR Text: '{}'",
                                payload.getForwardingId(),
                                dlr.getDestAddress().getAddress(),
                                dlrText);
                    } catch (InterruptedException e) {
                        logger.warn(
                                "DLR sending task for Message ID: {} was interrupted.",
                                payload.getForwardingId());
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        logger.error(
                                "Error sending DLR for Message ID: {}: {}",
                                payload.getForwardingId(),
                                e.getMessage(),
                                e);
                    }
                });
    }
}
