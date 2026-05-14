package gr.cytech.sendium.external;

/**
 * This class models a health check report.
 */
public class HealthCheckReport {
    private final String id;
    private final long lastUpdateTime;
    private final HealthCheckCode status;

    public HealthCheckReport(String id, HealthCheckCode status) {
        this(id, System.currentTimeMillis(), status);
    }

    public HealthCheckReport(String id, long lastUpdateTime, HealthCheckCode status) {
        this.id = id;
        this.lastUpdateTime = lastUpdateTime;
        this.status = status;
    }

    public static HealthCheckReport checkAndGet(HealthCheckReport report, HealthCheckCode status) {
        if (!report.getStatus().equals(status)) {
            return new HealthCheckReport(report.getId(), status);
        }
        return report;
    }

    public String getId() {
        return id;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public HealthCheckCode getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "HealthCheckReport{" + "id='" + id + '\'' +
                ", lastUpdateTime=" + lastUpdateTime +
                ", status=" + status +
                '}';
    }
}
