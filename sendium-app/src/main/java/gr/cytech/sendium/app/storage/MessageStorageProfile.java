package gr.cytech.sendium.app.storage;

public record MessageStorageProfile(String pending, String routerQueue, String routedWork) {
    public static final String PENDING_BACKEND = "sendium.message.pending.backend";
    public static final String ROUTER_BACKEND = "sendium.message.router-queue.backend";
    public static final String ROUTED_BACKEND = "sendium.message.routed-work.backend";
    public static final String PENDING_PATH = "sendium.message.pending.path";

    public MessageStorageProfile {
        if (!("memory".equals(pending) || "file".equals(pending)) || !"memory".equals(routerQueue) || !"memory".equals(routedWork)) {
            throw new IllegalArgumentException(
                    "messageStorageConfiguration|error|reason:unsupportedProfile|supported:memory/memory/memory,file/memory/memory" +
                            "|pendingSetting:" + PENDING_BACKEND + "|routerSetting:" + ROUTER_BACKEND +
                            "|routedSetting:" + ROUTED_BACKEND + "|fallback:none");
        }
    }

    @Override
    public String toString() {
        return pending + "/" + routerQueue + "/" + routedWork;
    }
}
