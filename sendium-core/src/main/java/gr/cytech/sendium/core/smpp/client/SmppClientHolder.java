package gr.cytech.sendium.core.smpp.client;

import com.cloudhopper.smpp.impl.DefaultSmppClient;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;

@ApplicationScoped
@RegisterForReflection(targets = { EpollSocketChannel.class, NioSocketChannel.class })
public class SmppClientHolder  {
    private static final Logger logger = LoggerFactory.getLogger(SmppClientHolder.class);
    protected DefaultSmppClient smppClient;
    protected int workerThreads = 4;
    protected int monitorThreads = 1;

    public SmppClientHolder() {
    }

    @PostConstruct
    public void init() {
        doStart();
    }

    void onStop(@Observes ShutdownEvent ev) throws Exception {
        stop();
    }

    public DefaultSmppClient getSmppClient() {
        return smppClient;
    }

    protected void doStart() {
        var old = smppClient;
        EventLoopGroup elg;
        if (Epoll.isAvailable()) {
            logger.info("Epoll is available! Using epoll event loop group");
            elg = new EpollEventLoopGroup(workerThreads,
                    Thread.ofPlatform().name("SmppClientEngine-Worker-", 1)
                            .uncaughtExceptionHandler((t, e) -> logger.warn("SmppClientEngineWorker|error|uncaught-exception", e)).factory());
        } else {
            elg = new NioEventLoopGroup(workerThreads,
                    Thread.ofPlatform().name("SmppClientEngine-Worker-", 1)
                            .uncaughtExceptionHandler((t, e) -> logger.warn("SmppClientEngineWorker|error|uncaught-exception", e)).factory());
        }
        var monitor = Executors.newScheduledThreadPool(monitorThreads,
                Thread.ofVirtual().name("SmppClientEngine-Monitor-", 1)
                        .uncaughtExceptionHandler((t, e) -> logger.warn("SmppClientEngineMonitor|error|uncaught-exception", e))
                        .factory());
        smppClient = new DefaultSmppClient(elg, monitor);
        closeEngine(old);
        logger.info("started SMPP Client Engine");
    }

    public void stop() throws Exception {
        closeEngine(smppClient);
        smppClient = null;
        logger.info("Stopped SMPP Client Engine");
    }

    public static void closeEngine(DefaultSmppClient old) {
        if (old == null) {
            return;
        }
        old.destroy();
    }
}

