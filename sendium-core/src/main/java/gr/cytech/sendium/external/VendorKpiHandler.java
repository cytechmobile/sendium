package gr.cytech.sendium.external;

import gr.cytech.sendium.conf.SendiumConfigurationProvider;

public interface VendorKpiHandler {

    void configure(SendiumConfigurationProvider cp, Object worker);

    int getFailureRate(WorkerResourceProvider workerResources);

    int getDeliveredRate(WorkerResourceProvider workerResources);

    int getPendingRate(WorkerResourceProvider workerResources);

    String getAcceptedFinalState(String dlr);

    boolean isEnabled();
}
