/**
 * $Revision$
 * $Date$
 *
 * Copyright (C) 1999-2005 Jive Software. All rights reserved.
 * This software is the proprietary information of Jive Software. Use is subject to license terms.
 */
package org.jivesoftware.util.cache;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.XMPPServerListener;
import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginClassLoader;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.util.InitializationException;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.JiveProperties;
import org.jivesoftware.util.Log;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates Cache objects. The returned caches will either be local or clustered
 * depending on the clustering enabled setting and a user's license.<p>
 * <p/>
 * When clustered caching is turned on, cache usage statistics for all caches
 * that have been created are periodically published to the clustered cache
 * named "opt-$cacheStats".
 *
 * @src.include false
 */
public class CacheFactory {

    public static String LOCAL_CACHE_PROPERTY_NAME = "cache.clustering.local.class";
    public static String CLUSTERED_CACHE_PROPERTY_NAME = "cache.clustering.clustered.class";

    private static boolean clusteringStarted = false;

    /**
     * Storage for all caches that get created.
     */
    private static Map<String, Cache> caches = new ConcurrentHashMap<String, Cache>();

    private static String localCacheFactoryClass;
    private static String clusteredCacheFactoryClass;
    private static CacheFactoryStrategy cacheFactoryStrategy;

    static {
        localCacheFactoryClass = JiveGlobals.getProperty(LOCAL_CACHE_PROPERTY_NAME,
                "org.jivesoftware.util.cache.DefaultLocalCacheStrategy");
        clusteredCacheFactoryClass = JiveGlobals.getProperty(CLUSTERED_CACHE_PROPERTY_NAME,
                "com.jivesoftware.util.cache.CoherenceClusteredCacheFactory");
    }

    private CacheFactory() {
    }

    /**
     * Returns an array of all caches in the system.
     * @return an array of all caches in the system.
     */
    public static Cache[] getAllCaches() {
        List<Cache> values = new ArrayList<Cache>();
        for (Cache cache : caches.values()) {
            values.add(cache);
        }
        return values.toArray(new Cache[values.size()]);
    }


    /**
     * Returns the named cache, creating it as necessary.
     *
     * @param name         the name of the cache to create.
     * @return the named cache, creating it as necessary.
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T extends Cache> T createCache(String name) {
        T cache = (T) caches.get(name);
        if (cache != null) {
            return cache;
        }

        cache = (T) cacheFactoryStrategy.createCache(name);

        return wrapCache(cache, name);
    }

    public static void lockKey(Object key, long timeout) {
        cacheFactoryStrategy.lockKey(key, timeout);
    }

    public static void unlockKey(Object key) {
        cacheFactoryStrategy.unlockKey(key);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Cache> T wrapCache(T cache, String name) {
        cache = (T) new CacheWrapper(cache);
        cache.setName(name);

        caches.put(name, cache);
        return cache;
    }

    /**
     * Returns true if this node is currently a member of a cluster. The last step of application
     * initialization is to join a cluster, so this method returns false during most of application startup.
     *
     * @return true if this node is currently a member of a cluster.
     */
    public static boolean isClusteringStarted() {
        return clusteringStarted;
    }

    /**
     * Returns a byte[] that uniquely identifies this member within the cluster or <tt>null</tt>
     * when not in a cluster.
     *
     * @return a byte[] that uniquely identifies this member within the cluster or null when not in a cluster.
     */
    public static byte[] getClusterMemberID() {
        return cacheFactoryStrategy.getClusterMemberID();
    }

    /**
     * Sets whether cache clustering should be enabled. Anytime this value is
     * changed, the application server must be restarted
     *
     * @param enabled true if cache clustering should be enabled.
     * @throws Exception if an error occurs while using the new cache type.
     */
    public static synchronized void setClusteringEnabled(boolean enabled) throws Exception {
        if (enabled == clusteringStarted) {
            return;
        }
        ClusterManager.setClusteringEnabled(enabled);
        if (!enabled) {
            stopClustering();
        }
        else {
            // Reload Jive properties. This will ensure that this nodes copy of the
            // properties starts correct.
           JiveProperties.getInstance().init();
           startClustering();
        }
    }

    public synchronized static void clearCaches() {
        for (String cacheName : caches.keySet()) {
            Cache cache = caches.get(cacheName);
            cache.clear();
        }
    }

    /**
     * Returns true if this member is the senior member in the cluster. If clustering
     * is not enabled, this method will also return true. This test is useful for
     * tasks that should only be run on a single member in a cluster.
     *
     * @return true if this cluster member is the senior or if clustering is not enabled.
     */
    public static boolean isSeniorClusterMember() {
        return cacheFactoryStrategy.isSeniorClusterMember();
    }

    /**
     * Invokes a task on other cluster members in an asynchronous fashion. The task will not be
     * executed on the local cluster member. If clustering is not enabled, this method
     * will do nothing.
     *
     * @param task the task to be invoked on all other cluster members.
     */
    public static void doClusterTask(final ClusterTask task) {
        if (!clusteringStarted) {
            return;
        }
        synchronized(CacheFactory.class) {
            if (!clusteringStarted) {
                return;
            }
        }

        cacheFactoryStrategy.doClusterTask(task);
    }

    /**
     * Invokes a task on a given cluster member in an asynchronous fashion. If clustering is not enabled,
     * this method will do nothing.
     *
     * @param task the task to be invoked on the specified cluster member.
     * @param nodeID the byte array that identifies the target cluster member.
     * @throws IllegalStateException if requested node was not found or not running in a cluster. 
     */
    public static void doClusterTask(final ClusterTask task, byte[] nodeID) {
        if (!clusteringStarted) {
            throw new IllegalStateException("Cluster service is not available");
        }
        synchronized(CacheFactory.class) {
            if (!clusteringStarted) {
                throw new IllegalStateException("Cluster service is not available");
            }
        }

        cacheFactoryStrategy.doClusterTask(task, nodeID);
    }

    /**
     * Invokes a task on other cluster members synchronously and returns the result as a Collection
     * (method will not return until the task has been executed on each cluster member).
     * The task will not be executed on the local cluster member. If clustering is not enabled,
     * this method will return an empty collection.
     *
     * @param task               the ClusterTask object to be invoked on all other cluster members.
     * @param includeLocalMember true to run the task on the local member, false otherwise
     * @return collection with the result of the execution.
     */
    public static Collection<Object> doSynchronousClusterTask(ClusterTask task, boolean includeLocalMember) {
        synchronized(CacheFactory.class) {
            if (!clusteringStarted) {
                return Collections.emptyList();
            }
        }

        return cacheFactoryStrategy.doSynchronousClusterTask(task, includeLocalMember);
    }

    /**
     * Invokes a task on a given cluster member synchronously and returns the result of
     * the remote operation. If clustering is not enabled, this method will return null.
     *
     * @param task        the ClusterTask object to be invoked on a given cluster member.
     * @param nodeID      the byte array that identifies the target cluster member.
     * @return result of remote operation or null if operation failed or operation returned null.
     * @throws IllegalStateException if requested node was not found or not running in a cluster.
     */
    public static Object doSynchronousClusterTask(ClusterTask task, byte[] nodeID) {
        synchronized(CacheFactory.class) {
            if (!clusteringStarted) {
                throw new IllegalStateException("Cluster service is not available");
            }
        }

        return cacheFactoryStrategy.doSynchronousClusterTask(task, nodeID);
    }

    /**
     * Shuts down the clustering service. This method should be called when the Jive
     * system is shutting down, and must not be called otherwise. By default, a
     * ServletContextListener is registered to listen for the web application shutting down, and
     * will automatically call this method. However, if the Jive system is being used in
     * another context, such as a command-line application, this method should be called
     * explicitly. Failing to call this method may temporarily impact cluster performance,
     * as the system will have to do extra work to recover from a non-clean shutdown.
     * If clustering is not enabled, this method will do nothing.
     */
    public static synchronized void shutdown() {
        if (!clusteringStarted) {
            return;
        }
        // See if clustering should be enabled.
        boolean enabled = ClusterManager.isClusteringEnabled();

        if (enabled) {
            Log.debug("Shutting down clustered cache service.");
            stopClustering();
        }
    }

    public static synchronized void initialize() throws InitializationException {
        try {
            cacheFactoryStrategy = (CacheFactoryStrategy) Class
                        .forName(localCacheFactoryClass).newInstance();
        }
        catch (InstantiationException e) {
             throw new InitializationException(e);
        }
        catch (IllegalAccessException e) {
             throw new InitializationException(e);
        }
        catch (ClassNotFoundException e) {
            throw new InitializationException(e);
        }
    }

    /**
     * Starts the cluster service if clustering is enabled, and begins tracking cache statistics. Before this method is called,
     * any {@link Cache}s returned by calls to {@link #createCache} will return local caches. The process of starting clustering
     * will recreate them as distributed caches. This is safer than the alternative - where clustering is started before
     * any  caches are created. In that scenario, cluster tasks can fire off in this process before it is safe for them to do so,
     * and cluster wide deadlocks can occur.
     */
    public static synchronized void startup() {

        if (clusteringStarted) {
            return;
        }
        // See if clustering should be enabled.
        boolean enabled = ClusterManager.isClusteringEnabled();

        // If the user tried to turn on clustering, make sure they're actually allowed to.
        if (enabled) {
            startClustering();
        }

        // Start a timing thread with 1 second of accuracy.
        Thread t = new Thread("Cache Stats") {
            private volatile boolean destroyed = false;

            public void run() {
                XMPPServer.getInstance().addServerListener(new XMPPServerListener() {
                    public void serverStarted() {}

                    public void serverStopping() {
                        destroyed = true;
                    }
                });

                // Run the timer indefinitely.
                while (!destroyed) {
                    // Publish cache stats for this cluster node (assuming clustering is
                    // enabled and there are stats to publish).
                    try {
                        cacheFactoryStrategy.updateCacheStats(caches);
                    }
                    catch (Exception e) {
                        Log.error(e);
                    }
                    try {
                        // Sleep 10 seconds.
                        sleep(10000);
                    }
                    catch (InterruptedException ie) {
                        // Ignore.
                    }
                }
                Log.debug("Cache stats thread terminated.");
            }
        };
        t.setDaemon(true);
        t.start();
    }

    private static ClassLoader getClusteredCacheStrategyClassLoader(String pluginName) {
        PluginManager pluginManager = XMPPServer.getInstance().getPluginManager();
        Plugin enterprisePlugin = pluginManager.getPlugin(pluginName);
        PluginClassLoader pluginLoader = pluginManager.getPluginClassloader(enterprisePlugin);
        if (pluginLoader != null) {
            return pluginLoader;
        }
        else {
            Log.warn("Unable to find PluginClassloader for plugin: " + pluginName + " in CacheFactory.");
            return Thread.currentThread().getContextClassLoader();
        }
    }

    private static void startClustering() {
        clusteringStarted = false;
        try {
            cacheFactoryStrategy = (CacheFactoryStrategy) Class.forName(clusteredCacheFactoryClass, true,
                    getClusteredCacheStrategyClassLoader("enterprise"))
                    .newInstance();
            clusteringStarted = cacheFactoryStrategy.startCluster();
        }
        catch (Exception e) {
            Log.error("Unable to start clustering - continuing in local mode", e);
        }
        if (!clusteringStarted) {
            // Revert to local cache factory if cluster fails to start
            try {
                cacheFactoryStrategy = (CacheFactoryStrategy) Class.forName(localCacheFactoryClass).newInstance();
            } catch (Exception e) {
                Log.error("Fatal error - Failed to join the cluster and failed to use local cache", e);
            }
        }
    }

    private static void stopClustering() {
        try {
            // Stop the cluster
            cacheFactoryStrategy.stopCluster();
            // Set the strategy to local
            cacheFactoryStrategy = (CacheFactoryStrategy) Class.forName(localCacheFactoryClass)
                    .newInstance();

            clusteringStarted = false;
        }
        catch (Exception e) {
            Log.error("Unable to stop clustering - continuing in clustered mode", e);
        }
    }

    /**
     * Notification message indicating that this JVM has joined a cluster.
     */
    public static void joinedCluster() {
        // Loop through local caches and switch them to clustered cache (migrate content)
        for (Cache cache : getAllCaches()) {
            CacheWrapper cacheWrapper = ((CacheWrapper) cache);
            Cache clusteredCache = cacheFactoryStrategy.createCache(cacheWrapper.getName());
            cacheWrapper.setWrappedCache(clusteredCache);
        }
    }

    /**
     * Notification message indicating that this JVM has left the cluster.
     */
    public static void leftCluster() {
        // Loop through clustered caches and change them to local caches (migrate content)
        try {
            CacheFactoryStrategy localStrategy = (CacheFactoryStrategy) Class.forName(localCacheFactoryClass).newInstance();

            for (Cache cache : getAllCaches()) {
                CacheWrapper cacheWrapper = ((CacheWrapper) cache);
                Cache standaloneCache = localStrategy.createCache(cacheWrapper.getName());
                cacheWrapper.setWrappedCache(standaloneCache);
            }
        } catch (Exception e) {
            Log.error("Error reverting caches to local caches", e);
        }
    }
}