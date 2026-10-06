package gr.cytech.sendium.app.storage;

public record MessageStorageProfile(String pending, String routerQueue, String routedWork) {
    public static final String PENDING_BACKEND = "sendium.message.pending.backend";
    public static final String ROUTER_BACKEND = "sendium.message.router-queue.backend";
    public static final String ROUTED_BACKEND = "sendium.message.routed-work.backend";

    public MessageStorageProfile {
        if (!"memory".equals(pending) || !"memory".equals(routerQueue) || !"memory".equals(routedWork)) {
            throw new IllegalArgumentException(
                    "Unsupported outbound message storage profile '" + pending + "/" + routerQueue + "/" + routedWork +
                            "' (pending/router-queue/routed-work). Supported profiles: memory/memory/memory. " +
                            "Configure " + PENDING_BACKEND + ", " + ROUTER_BACKEND + ", and " + ROUTED_BACKEND +
                            "; file and postgresql outbound backends are not implemented. No fallback is available.");
        }
    }

    @Override
    public String toString() {
        return pending + "/" + routerQueue + "/" + routedWork;
    }
}
