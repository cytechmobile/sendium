package gr.cytech.sendium.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Time;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class TimeUtils {
    public static final TimeZone UTC_TIMEZONE = TimeZone.getTimeZone("UTC");
    private static final Logger logger = LoggerFactory.getLogger(TimeUtils.class);
    Calendar targetTzCal;
    Calendar tstampCal;
    Calendar todCal;

    public TimeUtils(String targetTz) {
        try {
            targetTzCal = new GregorianCalendar(TimeZone.getTimeZone(targetTz));
        } catch (Exception e) {
            targetTzCal = new GregorianCalendar(TimeZone.getDefault());
        }

        tstampCal = Calendar.getInstance();

        todCal = Calendar.getInstance();
        todCal.setTimeInMillis(0);
    }

    public static void sleep(long period, TimeUnit timeUnit) {
        if (period <= 0) {
            return;
        }

        try {
            timeUnit.sleep(period);
        } catch (Exception e) {
            logger.warn("exception caught while sleeping for: {}{}", period, timeUnit);
        }
    }

    //CHECKSTYLE:OFF
    public static int getUTCMillisFromStartOfDayFromDBUTCTime(Time time) {
        //CHECKSTYLE:ON
        int offset = TimeZone.getDefault().getOffset(time.getTime());
        return (int) time.getTime() + offset;
    }

    public static Time getUTCTimeFromStartOfDayFromString(String time) throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        sdf.setTimeZone(UTC_TIMEZONE);
        //int offset = TimeZone.getDefault().getOffset(new Date().getTime());
        return new Time(sdf.parse(time).getTime() /*- offset*/);
    }

    public static int getUTCMillisFromStartOfDayFromString(String time) throws ParseException {
        return (int) getUTCTimeFromStartOfDayFromString(time).getTime();
    }
}
