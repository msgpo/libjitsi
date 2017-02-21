/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.impl.neomedia.rtp;

import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.concurrent.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * An simple interface which allows a packet to be retrieved from a
 * cache/storage by an SSRC identifier and a sequence number.
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class RawPacketCache
    implements AutoCloseable
{
    /**
     * The <tt>Logger</tt> used by the <tt>RawPacketCache</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(RawPacketCache.class);

    /**
     * The <tt>ConfigurationService</tt> used to load caching configuration.
     */
    private final static ConfigurationService cfg
        = LibJitsi.getConfigurationService();

    /**
     * Configuration property for number of streams to cache
     */
    public final static String NACK_CACHE_SIZE_STREAMS
        = "org.jitsi.impl.neomedia.transform.CachingTransformer.CACHE_SIZE_STREAMS";

    /**
     * Configuration property number of packets to cache.
     */
    public final static String NACK_CACHE_SIZE_PACKETS
        = "org.jitsi.impl.neomedia.transform.CachingTransformer.CACHE_SIZE_PACKETS";

    /**
     * Configuration property for nack cache size in milliseconds.
     */
    public final static String NACK_CACHE_SIZE_MILLIS
        = "org.jitsi.impl.neomedia.transform.CachingTransformer.CACHE_SIZE_MILLIS";

    /**
     * Packets added to the cache more than <tt>SIZE_MILLIS</tt> ago might be
     * cleared from the cache.
     */
    private static int SIZE_MILLIS = cfg.getInt(NACK_CACHE_SIZE_MILLIS, 500);

    /**
     * Assumed rate of the RTP clock.
     */
    private static int RTP_CLOCK_RATE = 90000;

    /**
     * <tt>SIZE_MILLIS</tt> expressed as a number of ticks on the RTP clock.
     */
    private static int SIZE_RTP_CLOCK_TICKS
        = (RTP_CLOCK_RATE / 1000) * SIZE_MILLIS;

    /**
     * The maximum number of different SSRCs for which a cache will be created.
     */
    private static int MAX_SSRC_COUNT = cfg.getInt(NACK_CACHE_SIZE_STREAMS, 50);

    /**
     * The maximum number of packets cached for each SSRC.
     */
    private static int MAX_SIZE_PACKETS = cfg.getInt(NACK_CACHE_SIZE_PACKETS, 200);

    /**
     * The size of {@link #pool} and {@link #containersPool}.
     */
    private static int POOL_SIZE = 100;

    /**
     * The amount of time, after which the cache for an SSRC will be cleared,
     * unless new packets have been inserted.
     */
    private static int SSRC_TIMEOUT_MILLIS = SIZE_MILLIS + 50;

    /**
     * Returns <tt>true</tt> iff <tt>a</tt> is less than <tt>b</tt> modulo 2^32.
     */
    private static boolean lessThanTS(long a, long b)
    {
        if (a == b)
            return false;
        else if (a > b)
            return a - b >= (1L << 31);
        else //a < b
            return b - a < (1L << 31);
    }

    /**
     * The pool of <tt>RawPacket</tt>s which we use to avoid allocation and GC.
     */
    private final Queue<RawPacket> pool
        = new LinkedBlockingQueue<>(POOL_SIZE);

    /**
     * A cache of unused {@link Container} instances.
     */
    private final Queue<Container> containersPool
        = new LinkedBlockingQueue<>(POOL_SIZE);

    /**
     * An object used to synchronize access to {@link #sizeInBytes},
     * {@link #maxSizeInBytes}, {@link #sizeInPackets} and
     * {@link #maxSizeInPackets}.
     */
    private final Object sizesSyncRoot = new Object();

    /**
     * The current size in bytes of the cache (for all SSRCs combined).
     */
    private int sizeInBytes = 0;

    /**
     * The maximum reached size in bytes of the cache (for all SSRCs combined).
     */
    private int maxSizeInBytes = 0;

    /**
     * The current number of packets in the cache (for all SSRCs combined).
     */
    private int sizeInPackets = 0;

    /**
     * The maximum reached number of packets in the cache (for all SSRCs
     * combined).
     */
    private int maxSizeInPackets = 0;

    /**
     * Counts the number of requests (calls to {@link #get(long, int)}) which
     * the cache was able to answer.
     */
    private AtomicInteger totalHits = new AtomicInteger(0);

    /**
     * Counts the number of requests (calls to {@link #get(long, int)}) which
     * the cache was not able to answer.
     */
    private AtomicInteger totalMisses = new AtomicInteger(0);

    /**
     * Counts the total number of packets added to this cache.
     */
    private AtomicInteger totalPacketsAdded = new AtomicInteger(0);

    /**
     * Contains a <tt>Cache</tt> instance for each SSRC.
     */
    private final Map<Long, Cache> caches = new HashMap<>();

    /**
     * The age in milliseconds of the oldest packet retrieved from any of the
     * {@link Cache}s of this instance.
     */
    private MonotonicAtomicLong oldestHit = new MonotonicAtomicLong();

    /**
     * The hash code or other identifier of the owning stream, if any. Only
     * used for logging.
     */
    private final int streamId;

    /**
     * Initializes a new {@link CachingTransformer} instance.
     * @param streamId the identifier of the owning stream.
     */
    public RawPacketCache(int streamId)
    {
        this.streamId = streamId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
        throws Exception
    {
        if (totalPacketsAdded.get() > 0)
        {
            logger.info(Logger.Category.STATISTICS,
                "closed,stream=" + streamId
                    + " max_size_bytes=" + maxSizeInBytes
                    + ",max_size_packets=" + maxSizeInPackets
                    + ",total_hits=" + totalHits.get()
                    + ",total_misses=" + totalMisses.get()
                    + ",total_packets=" + totalPacketsAdded.get()
                    + ",oldest_hit_ms=" + oldestHit);
        }

        synchronized (caches)
        {
            caches.clear();
        }
        pool.clear();
        containersPool.clear();
    }

    /**
     * Gets the packet, encapsulated in a {@link Container} with the given SSRC
     * and RTP sequence number from the cache. If no such packet is found,
     * returns <tt>null</tt>.
     * @param ssrc The SSRC of the packet.
     * @param seq the RTP sequence number of the packet.
     * @return the packet, encapsulated in a {@link Container} with the given
     * SSRC and RTP sequence number from the cache. If no such packet is found,
     * returns <tt>null</tt>.
     */
    public Container getContainer(long ssrc, int seq)
    {
        Cache cache = getCache(ssrc & 0xffffffffL, false);

        Container container = cache != null ? cache.get(seq) : null;

        if (container != null)
        {
            if (container.timeAdded > 0)
            {
                oldestHit
                    .increase(System.currentTimeMillis() - container.timeAdded);
            }
            totalHits.incrementAndGet();
        }
        else
        {
            totalMisses.incrementAndGet();
        }

        return container;
    }

    /**
     * Gets the packet with the given SSRC and RTP sequence number from the
     * cache. If no such packet is found, returns <tt>null</tt>.
     * @param ssrc The SSRC of the packet.
     * @param seq the RTP sequence number of the packet.
     * @return the packet with the given SSRC and RTP sequence number from the
     * cache. If no such packet is found, returns <tt>null</tt>.
     */
    public RawPacket get(long ssrc, int seq)
    {
        Container container = getContainer(ssrc, seq);
        return container == null ? null : container.pkt;
    }

    /**
     * Gets the {@link Cache} instance which caches packets with SSRC
     * <tt>ssrc</tt>, creating if <tt>create</tt> is set and creation is
     * possible (the maximum number of caches hasn't been reached).
     * @param ssrc the SSRC.
     * @param create whether to create an instance if one doesn't already exist.
     * @return the cache for <tt>ssrc</tt> or <tt>null</tt>.
     */
    private Cache getCache(long ssrc, boolean create)
    {
        synchronized (caches)
        {
            Cache cache = caches.get(ssrc);
            if (cache == null && create)
            {
                if (caches.size() < MAX_SSRC_COUNT)
                {
                    cache = new Cache();
                    caches.put(ssrc, cache);
                }
                else
                {
                    logger.warn("Not creating a new cache for SSRC " + ssrc
                        + ": too many SSRCs already cached.");
                }
            }

            return cache;
        }
    }

    /**
     * Saves a packet in the cache.
     * @param pkt the packet to save.
     */
    public void cachePacket(RawPacket pkt)
    {
        Cache cache = getCache(pkt.getSSRCAsLong(), true);

        if (cache != null)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Caching a packet. SSRC=" + pkt.getSSRCAsLong()
                    + " seq=" + pkt.getSequenceNumber());
            }
            cache.insert(pkt);
            totalPacketsAdded.incrementAndGet();
        }
    }



    /**
     * Gets an unused <tt>RawPacket</tt> with at least <tt>len</tt> bytes of
     * buffer space.
     * @param len the minimum available length
     * @return An unused <tt>RawPacket</tt> with at least <tt>len</tt> bytes of
     * buffer space.
     */
    private RawPacket getFreePacket(int len)
    {
        RawPacket pkt = pool.poll();
        if (pkt == null)
            pkt = new RawPacket(new byte[len], 0, 0);

        if (pkt.getBuffer() == null || pkt.getBuffer().length < len)
            pkt.setBuffer(new byte[len]);
        pkt.setOffset(0);
        pkt.setLength(0);

        return pkt;
    }

    /**
     * @return  an unused {@link Container} instance.
     */
    private Container getFreeContainer()
    {
        Container container = containersPool.poll();
        if (container == null)
        {
            container = new Container();
        }
        return container;
    }

    /**
     * Checks for {@link Cache} instances which have not received new packets
     * for a period longer than {@link #SSRC_TIMEOUT_MILLIS} and removes them.
     */
    public void clean(long now)
    {
        synchronized (caches)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Cleaning CachingTransformer " + hashCode());
            }

            Iterator<Map.Entry<Long,Cache>> iter
                = caches.entrySet().iterator();
            while (iter.hasNext())
            {
                Map.Entry<Long,Cache> entry = iter.next();
                Cache cache = entry.getValue();
                if (cache.lastInsertTime + SSRC_TIMEOUT_MILLIS < now)
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("Removing cache for SSRC " + entry
                            .getKey());
                    }
                    cache.empty();
                    iter.remove();
                }
            }
        }
    }

    /**
     * Returns a {@link Container} and its {@link RawPacket} to the list of
     * free containers (and packets).
     * @param container the container to return.
     */
    private void returnContainer(Container container)
    {
        if (container != null)
        {
            if (container.pkt != null)
            {
                pool.offer(container.pkt);
            }

            container.pkt = null;
            containersPool.offer(container);
        }
    }

    /**
     * Implements a cache for the packets of a specific SSRC.
     */
    private class Cache
    {
        /**
         * The underlying container. It maps a packet index (based on its RTP
         * sequence number, in the same way as used in SRTP (RFC3711)) to the
         * <tt>RawPacket</tt> with the packet contents.
         */
        private TreeMap<Integer, Container> cache = new TreeMap<>();

        /**
         * Last system time of insertion of a packet in this cache.
         */
        private long lastInsertTime = -1;

        /**
         * A Roll Over Counter (as in by RFC3711).
         */
        private int ROC = 0;

        /**
         * The highest received sequence number (as in RFC3711).
         */
        private int s_l = -1;

        /**
         * Inserts a packet into this <tt>Cache</tt>.
         * @param pkt the packet to insert.
         */
        private synchronized void insert(RawPacket pkt)
        {
            int len = pkt.getLength();
            RawPacket cachePacket = getFreePacket(len);
            System.arraycopy(pkt.getBuffer(), pkt.getOffset(),
                cachePacket.getBuffer(), 0,
                len);
            cachePacket.setLength(len);

            int index = calculateIndex(pkt.getSequenceNumber());
            Container container = getFreeContainer();
            container.pkt = cachePacket;
            container.timeAdded = System.currentTimeMillis();

            // If the packet is already in the cache, we want to update the
            // timeAdded field for retransmission purposes. This is implemented
            // by simply replacing the old packet.
            Container oldContainer = cache.put(index, container);

            synchronized (sizesSyncRoot)
            {
                sizeInPackets++;
                sizeInBytes += len;
                if (oldContainer != null)
                {
                    sizeInPackets--;
                    sizeInBytes -= oldContainer.pkt.getLength();
                }

                if (sizeInPackets > maxSizeInPackets)
                    maxSizeInPackets = sizeInPackets;
                if (sizeInBytes > maxSizeInBytes)
                    maxSizeInBytes = sizeInBytes;
            }

            returnContainer(oldContainer);
            lastInsertTime = System.currentTimeMillis();
            clean();
        }


        /**
         * Calculates the index of an RTP packet based on its RTP sequence
         * number and updates the <tt>s_l</tt> and <tt>ROC</tt> fields. Based
         * on the procedure outlined in RFC3711
         * @param seq the RTP sequence number of the RTP packet.
         * @return the index of the RTP sequence number with sequence number
         * <tt>seq</tt>.
         */
        private int calculateIndex(int seq)
        {
            if (s_l == -1)
            {
                s_l = seq;
                return seq;
            }

            int v = ROC;
            if (s_l < (1<<15))
                if (seq - s_l > (1<<15))
                    v = (int) ((ROC-1) % (1L<<32));
                else if (s_l - (1<<16) > seq)
                    v = (int) ((ROC+1) % (1L<<32));

            if (v == ROC && seq > s_l)
                s_l = seq;
            else if (v == ((ROC + 1) % (1L<<32)))
            {
                s_l = seq;
                ROC = v;
            }


            return seq + v * (1<<16);
        }

        /**
         * Returns the RTP packet with sequence number <tt>seq</tt> from the
         * cache, or <tt>null</tt> if the cache does not contain a packet with
         * this sequence number.
         * @param seq the RTP sequence number of the packet to get.
         * @return the RTP packet with sequence number <tt>seq</tt> from the
         * cache, or <tt>null</tt> if the cache does not contain a packet with
         * this sequence number.
         */
        private synchronized Container get(int seq)
        {
            // Since sequence numbers wrap at 2^16, we can't know with absolute
            // certainty which packet the request refers to. We assume that it
            // is for the latest packet (i.e. the one with the highest index).
            Container pkt = cache.get(seq + ROC * (1 << 16));

            // Maybe the ROC was just bumped recently.
            if (pkt == null && ROC > 0)
                pkt = cache.get(seq + (ROC-1)*(1<<16));

            // Since the cache only stores <tt>SIZE_MILLIS</tt> milliseconds of
            // packets, we assume that it doesn't contain packets spanning
            // more than one ROC.

            return
                pkt == null
                    ? null
                    : new Container(
                    new RawPacket(pkt.pkt.getBuffer().clone(),
                        pkt.pkt.getOffset(),
                        pkt.pkt.getLength()),
                    pkt.timeAdded);
        }

        /**
         * Drops the oldest packets from the cache until:
         * 1. The cache contains at most {@link #MAX_SIZE_PACKETS} packets, and
         * 2. The cache only contains packets at most {@link #SIZE_MILLIS}
         * milliseconds older than the newest packet in the cache.
         */
        private synchronized void clean()
        {
            int size = cache.size();
            if (size <= 0)
                return;

            long lastTimestamp
                = 0xffffffffL & cache.lastEntry().getValue().pkt.getTimestamp();
            long cleanBefore = getCleanBefore(lastTimestamp);

            Iterator<Map.Entry<Integer,Container>> iter
                = cache.entrySet().iterator();
            int removedPackets = 0;
            int removedBytes = 0;
            while (iter.hasNext())
            {
                Container container = iter.next().getValue();
                RawPacket pkt = container.pkt;

                if (size > MAX_SIZE_PACKETS)
                {
                    // Remove until we go under the max size, regardless of the
                    // timestamps.
                    size--;
                }
                else if (lessThanTS(cleanBefore,
                    0xffffffffL & pkt.getTimestamp()))
                {
                    // We reached a packet with a timestamp after 'cleanBefore'.
                    // The rest of the packets are even more recent.
                    break;
                }

                iter.remove();
                removedBytes += pkt.getLength();
                removedPackets++;
                returnContainer(container);
            }

            synchronized (sizesSyncRoot)
            {
                sizeInBytes -= removedBytes;
                sizeInPackets -= removedPackets;
            }

        }

        synchronized private void empty()
        {
            int removedBytes = 0;
            for (Container container : cache.values())
            {
                removedBytes += container.pkt.getBuffer().length;
                returnContainer(container);
            }

            synchronized (sizesSyncRoot)
            {
                sizeInPackets -= cache.size();
                sizeInBytes -= removedBytes;
            }

            cache.clear();
        }

        /**
         * Returns the RTP timestamp which is {@link #SIZE_MILLIS} milliseconds
         * older than <tt>ts</tt>.
         * @param ts
         * @return
         */
        private long getCleanBefore(long ts)
        {
            return (ts + (1L<<32) - SIZE_RTP_CLOCK_TICKS) % (1L<<32);
        }
    }

    /**
     * A container for packets in the cache.
     */
    public class Container
    {
        /**
         * The {@link RawPacket} which this container holds.
         */
        public RawPacket pkt;

        /**
         * The time (in milliseconds since the epoch) that the packet was
         * added to the cache.
         */
        public long timeAdded;

        /**
         * Initializes a new empty {@link Container} instance.
         */
        public Container()
        {
            this(null, -1);
        }

        /**
         * Initializes a new {@link Container} instance.
         * @param pkt the packet to hold.
         * @param timeAdded the time the packet was added.
         */
        public Container(RawPacket pkt, long timeAdded)
        {
            this.pkt = pkt;
            this.timeAdded = timeAdded;
        }
    }

}