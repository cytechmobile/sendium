package gr.cytech.sendium.external;

import gr.cytech.sendium.conf.SendiumConfigurationProvider;

public class NoOpVendorKpiHandler implements VendorKpiHandler {

    @Override
    public void configure(SendiumConfigurationProvider cp, Object worker) {
        // No operation
    }

    @Override
    public int getFailureRate(WorkerResourceProvider workerResources) {
        return -1;
    }

    @Override
    public int getDeliveredRate(WorkerResourceProvider workerResources) {
        return -1;
    }

    @Override
    public int getPendingRate(WorkerResourceProvider workerResources) {
        return -1;
    }

    @Override
    public String getAcceptedFinalState(String dlr) {
        return null;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
