package gr.cytech.sendium.util;

import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTraceTest {

    @Test
    void identifiersIncludeSafeSupportContext() {
        StandardMessage message = new StandardMessage();
        message.serial = "gw-1";
        message.msgId = 17;
        message.extrid = "operator-1";
        message.type = StandardMessage.MSG_DLR;
        message.priority = StandardMessage.HIGH_PRIORITY;
        message.acked = true;
        message.dcs = StandardMessage.DCS_16BIT;
        message.mclass = 2;
        message.ttl = 60;
        message.ddt = 10;
        message.state = StandardMessage.DLR_STAT_DELIVRD;
        message.errcode = "0";
        message.rtxCnt = 2;
        message.owner_id = "account-1";
        message.systemId = "system-1";
        message.message_center = "smsc-1";
        message.ingateway = "smpp.in";
        message.outgateway = "smpp.out";
        message.nextTarget = "fallback";
        message.onetwork = 20201;
        message.cnetwork = 20205;
        message.smsCnt = 2;
        message.smsSubmitCnt = 3;
        message.reassembledParts = new ArrayList<>(List.of("part-1", "part-2"));
        message.binheader = "UDH-SECRET";
        message.tlvs = new HashMap<>(Map.of("carrier_tag", "TLV-SECRET"));

        String trace = MessageTrace.identifiers(message);

        assertThat(trace).contains(
                "serial=gw-1",
                "msgId=17",
                "extrid=operator-1",
                "type=l",
                "typeId=18",
                "priority=3",
                "acked=true",
                "dcs=8",
                "mclass=2",
                "ttl=60",
                "ddt=10",
                "state=1",
                "errcode=0",
                "rtxCnt=2",
                "accountId=account-1",
                "systemId=system-1",
                "messageCenter=smsc-1",
                "ingateway=smpp.in",
                "outgateway=smpp.out",
                "nextTarget=fallback",
                "onetwork=20201",
                "cnetwork=20205",
                "smsCnt=2",
                "submitParts=3",
                "reassembledParts=2",
                "hasUdh=true",
                "tlvCount=1");
    }

    @Test
    void identifiersDoNotIncludePayloadAddressesOrArbitraryFields() {
        StandardMessage message = new StandardMessage();
        message.from = "FROM-SECRET";
        message.to = "TO-SECRET";
        message.body = "BODY-SECRET";
        message.binbody = "BINBODY-SECRET";
        message.binheader = "BINHEADER-SECRET";
        message.metadata = "METADATA-SECRET";
        message.field1 = "FIELD-SECRET";
        message.attrs = new HashMap<>(Map.of("attr", "ATTR-SECRET"));
        message.tlvs = new HashMap<>(Map.of("tlv", "TLV-SECRET"));

        String trace = MessageTrace.identifiers(message);

        assertThat(trace).doesNotContain(
                "FROM-SECRET",
                "TO-SECRET",
                "BODY-SECRET",
                "BINBODY-SECRET",
                "BINHEADER-SECRET",
                "METADATA-SECRET",
                "FIELD-SECRET",
                "ATTR-SECRET",
                "TLV-SECRET");
    }

    @Test
    void identifiersUseNamedViberType() {
        StandardMessage message = new StandardMessage();
        message.type = StandardMessage.MSG_VIBER;

        assertThat(MessageTrace.identifiers(message)).contains("type=v", "typeId=22");
    }

    @Test
    void shouldLogDisablesAllMessageFlowEventsWhenModeIsOff() {
        assertThat(MessageTrace.shouldLog("off", MessageTrace.EVENT_ACCEPTED)).isFalse();
        assertThat(MessageTrace.shouldLog("off", MessageTrace.EVENT_SUBMITTED)).isFalse();
        assertThat(MessageTrace.shouldLog("off", MessageTrace.EVENT_DLR)).isFalse();
        assertThat(MessageTrace.shouldLog("off", MessageTrace.EVENT_DELIVER_SENT)).isFalse();
        assertThat(MessageTrace.shouldLog("off", MessageTrace.EVENT_ROUTED)).isFalse();
    }

    @Test
    void shouldLogAllowsOnlyNecessaryMessageFlowEventsByDefault() {
        assertThat(MessageTrace.shouldLog((String) null, MessageTrace.EVENT_ACCEPTED)).isTrue();
        assertThat(MessageTrace.shouldLog("necessary", MessageTrace.EVENT_SUBMITTED)).isTrue();
        assertThat(MessageTrace.shouldLog(" necessary ", MessageTrace.EVENT_DLR)).isTrue();
        assertThat(MessageTrace.shouldLog("invalid", MessageTrace.EVENT_DELIVER_SENT)).isTrue();
        assertThat(MessageTrace.shouldLog("necessary", MessageTrace.EVENT_ENQUEUED)).isFalse();
        assertThat(MessageTrace.shouldLog("necessary", MessageTrace.EVENT_ROUTED)).isFalse();
        assertThat(MessageTrace.shouldLog("necessary", MessageTrace.EVENT_SUBMIT_RESPONSE)).isFalse();
    }

    @Test
    void shouldLogAllowsEveryMessageFlowEventWhenModeIsAll() {
        assertThat(MessageTrace.shouldLog("all", MessageTrace.EVENT_ACCEPTED)).isTrue();
        assertThat(MessageTrace.shouldLog("all", MessageTrace.EVENT_ENQUEUED)).isTrue();
        assertThat(MessageTrace.shouldLog("all", MessageTrace.EVENT_ROUTED)).isTrue();
        assertThat(MessageTrace.shouldLog("all", MessageTrace.EVENT_SUBMIT_RESPONSE)).isTrue();
    }
}
