package smsgateway.providers;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogProvider {
    public static final String CATEGORY_HTTP = "sms-gateway.http";
    public static final String CATEGORY_SMPP_SERVER = "sms-gateway.smpp.server";
    public static final String CATEGORY_SMPP_CLIENT = "sms-gateway.smpp.client";
    public static final String CATEGORY_ROUTING = "sms-gateway.routing";

    public static Logger getHttpLogger(String loggerName) {
        return LoggerFactory.getLogger(getCategorizedLoggerName(CATEGORY_HTTP, loggerName));
    }

    public static Logger getSmppServerLogger(String loggerName) {
        return LoggerFactory.getLogger(getCategorizedLoggerName(CATEGORY_SMPP_SERVER, loggerName));
    }

    public static Logger getSmppClientLogger(String loggerName) {
        return LoggerFactory.getLogger(getCategorizedLoggerName(CATEGORY_SMPP_CLIENT, loggerName));
    }

    public static Logger getRoutingLogger(String loggerName) {
        return LoggerFactory.getLogger(getCategorizedLoggerName(CATEGORY_ROUTING, loggerName));
    }

    public static String getCategorizedLoggerName(String category, String loggerName) {
        loggerName = Strings.nullToEmpty(loggerName);
        category = Strings.nullToEmpty(category);
        if (loggerName.startsWith(category)) {
            return loggerName;
        } else if (loggerName.startsWith(".")) {
            loggerName = loggerName.substring(1);
        }

        return category + "." + loggerName;
    }
}
