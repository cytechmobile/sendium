package gr.cytech.sendium.external;

/**
 * This interface must be implemented by a module or subsystem that want to report
 * its health status.
 */
public interface HealthCheckReporter {

    /**
     * @return The last known health status report.
     */
    public HealthCheckReport getHealthCheckReport();

    /**
     * @return Calculates and returns the current health status report.
     */
    public HealthCheckReport checkAndGetHealthCheckReport();

}
