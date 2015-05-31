package net.iponweb.disthene.service.index;

import net.engio.mbassy.bus.MBassador;
import net.engio.mbassy.listener.Handler;
import net.engio.mbassy.listener.Listener;
import net.engio.mbassy.listener.References;
import net.iponweb.disthene.bean.Metric;
import net.iponweb.disthene.config.IndexConfiguration;
import net.iponweb.disthene.events.DistheneEvent;
import net.iponweb.disthene.events.MetricStoreEvent;
import net.iponweb.disthene.util.NamedThreadFactory;
import org.apache.log4j.Logger;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;

import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Andrei Ivanov
 */
@Listener(references= References.Strong)
public class IndexService {
    private static final String SCHEDULER_NAME = "distheneIndexCacheExpire";

    private Logger logger = Logger.getLogger(IndexService.class);

    private IndexConfiguration indexConfiguration;
    private TransportClient client;
    private IndexThread indexThread;

    // tenant -> path -> dummy
    private ConcurrentMap<String, ConcurrentMap<String, AtomicLong>> cache = new ConcurrentHashMap<>();
    private Queue<Metric> metrics = new ConcurrentLinkedQueue<>();

    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, new NamedThreadFactory(SCHEDULER_NAME));


    public IndexService(IndexConfiguration indexConfiguration, MBassador<DistheneEvent> bus) {
        this.indexConfiguration = indexConfiguration;

        bus.subscribe(this);

        Settings settings = ImmutableSettings.settingsBuilder()
                .put("cluster.name", indexConfiguration.getName())
                .build();
        client = new TransportClient(settings);
        for (String node : indexConfiguration.getCluster()) {
            client.addTransportAddress(new InetSocketTransportAddress(node, indexConfiguration.getPort()));
        }

        indexThread = new IndexThread(
                "distheneIndexThread",
                client,
                metrics,
                indexConfiguration.getIndex(),
                indexConfiguration.getType(),
                indexConfiguration.getBulk().getActions(),
                indexConfiguration.getBulk().getInterval()
        );

        indexThread.start();

        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                expireCache();
            }
        }, indexConfiguration.getExpire(), indexConfiguration.getExpire(), TimeUnit.SECONDS);


    }

    private ConcurrentMap<String, AtomicLong> getTenantPaths(String tenant) {
        ConcurrentMap<String, AtomicLong> tenantPaths = cache.get(tenant);
        if (tenantPaths == null) {
            ConcurrentMap<String, AtomicLong> newTenantPaths = new ConcurrentHashMap<>();
            tenantPaths = cache.putIfAbsent(tenant, newTenantPaths);
            if (tenantPaths == null) {
                tenantPaths = newTenantPaths;
            }
        }

        return tenantPaths;
    }

    @Handler(rejectSubtypes = false)
    public void handle(MetricStoreEvent metricStoreEvent) {
        ConcurrentMap<String, AtomicLong> tenantPaths = getTenantPaths(metricStoreEvent.getMetric().getTenant());
        AtomicLong lastSeen = tenantPaths.get(metricStoreEvent.getMetric().getPath());

        if (lastSeen == null) {
            lastSeen = tenantPaths.putIfAbsent(metricStoreEvent.getMetric().getPath(), new AtomicLong(System.currentTimeMillis() / 1000L));
            if (lastSeen == null) {
                metrics.offer(metricStoreEvent.getMetric());
            } else {
                lastSeen.getAndSet(System.currentTimeMillis() / 1000L);
            }
        }
    }

    private synchronized void expireCache() {
        logger.debug("Trying index cache expiration");

        long currentTimestamp = System.currentTimeMillis() / 1000L;
        int pathsRemoved = 0;

        for(ConcurrentMap<String, AtomicLong> tenantMap : cache.values()) {
            for(Iterator<Map.Entry<String, AtomicLong>> iterator = tenantMap.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, AtomicLong> entry = iterator.next();
                if (entry.getValue().get() < currentTimestamp - indexConfiguration.getExpire()) {
                    iterator.remove();
                    pathsRemoved++;
                }
            }
        }

        logger.debug("Expired " + pathsRemoved + " paths from index cache");
    }

    public void shutdown() {
        scheduler.shutdown();
        indexThread.shutdown();
        logger.info("Sleeping for 10 seconds to allow leftovers to be written");
        try {
            Thread.sleep(10000);
        } catch (InterruptedException ignored) {
        }
        logger.info("Closing ES client");
        client.close();
    }
}
