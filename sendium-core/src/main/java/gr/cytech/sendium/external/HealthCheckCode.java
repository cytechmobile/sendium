package gr.cytech.sendium.external;

/**
 * This enumeration defines all the different status codes that a HealthCheckListener can
 * return.
 *
 * <p>Status Codes:
 * OK: The module is running and it is performing as intended
 * PAUSED: The module running but it is paused
 * BUSY: The module is running but it experiences some performance difficulties (e.g. great load, queues)
 * DEGRADED: The module is running but it experiences some failures (e.g. occasional connectivity issues)
 * DEAD: The module is running but it cannot perform its work (e.g. permanent connectivity issues)
 * DOWN: The module is not running at all
 * </p>
 */
public enum HealthCheckCode {
    OK(false),

    PAUSED(false), BUSY(false), DEGRADED(false),

    DEAD(true), DOWN(true);

    private final boolean critical;

    HealthCheckCode(boolean critical) {
        this.critical = critical;
    }

    public boolean isCritical() {
        return critical;
    }

    public String toString() {
        return this.name();
    }
}
