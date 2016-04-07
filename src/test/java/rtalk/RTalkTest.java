package rtalk;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.junit.Assert.*;
import static redis.clients.jedis.Protocol.DEFAULT_PORT;
import static redis.clients.jedis.Protocol.DEFAULT_TIMEOUT;
import static rtalk.RTalk.BURIED;
import static rtalk.RTalk.INSERTED;
import static rtalk.RTalk.KICKED;
import static rtalk.RTalk.NOT_FOUND;
import static rtalk.RTalk.RESERVED;
import static rtalk.RTalk.TIMED_OUT;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.Before;
import org.junit.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import rtalk.RTalk.Job;
import rtalk.RTalk.Response;

public class RTalkTest {

    private JedisPool jedisPool;

    public JedisPool getJedisPool() {
        return new JedisPool(poolConfig(), "localhost", DEFAULT_PORT, DEFAULT_TIMEOUT, null, 9);
    }

    public static GenericObjectPoolConfig poolConfig() {
        GenericObjectPoolConfig pc = new GenericObjectPoolConfig();
        pc.setMaxTotal(160);
        pc.setTestOnBorrow(true);
        pc.setMinIdle(1);
        pc.setMaxIdle(5);
        pc.setTestWhileIdle(true);
        return pc;
    }

    @Before
    public void setup() {
        this.jedisPool = getJedisPool();
        try (Jedis j = jedisPool.getResource()) {
            j.flushDB();
        }
    }

    @Test
    public void testPutTimeoutTTR() throws Exception {
        RTalk rt = new RTalk(jedisPool);
        Response put1 = rt.put(0, 0, 1000, "a");
        assertEquals(INSERTED, put1.status);
        assertEquals(1, rt.statsTube().currentjobsready);
        Response reserve = rt.reserve();
        assertEquals(RESERVED, reserve.status);
        assertEquals(put1.id, reserve.id);
        assertEquals(0, rt.statsTube().currentjobsready);
        Thread.sleep(1500);
        assertEquals(1, rt.statsTube().currentjobsready);

        Response reserveAfterWorkerTimeout = rt.reserve();
        assertEquals(RESERVED, reserveAfterWorkerTimeout.status);
        assertEquals(put1.id, reserveAfterWorkerTimeout.id);

    }

    @Test
    public void testPutReserve() throws Exception {
        RTalk rt = new RTalk(jedisPool);
        String expectedData = "{hello: 'world'}";

        Response put1 = rt.put(1, 0, 0, "{}");
        assertEquals(INSERTED, put1.status);
        assertTrue(isNotBlank(put1.id));

        Response put2 = rt.put(0, 0, 0, expectedData);
        assertEquals(INSERTED, put2.status);
        assertTrue(isNotBlank(put2.id));

        Response reserve = rt.reserve();

        assertEquals(RESERVED, reserve.status);
        assertEquals(put2.id, reserve.id);
        assertEquals(expectedData, reserve.data);

        assertEquals(1L, rt.statsJob(reserve.id).reserves);

        assertEquals(RESERVED, rt.reserve().status);
        assertEquals(TIMED_OUT, rt.reserve().status);
    }

    @Test
    public void testPutPriorityReserve() throws Exception {
        RTalk rt = new RTalk(jedisPool);
        Response put1 = rt.put(0, 0, 0, "a");
        assertEquals(INSERTED, put1.status);

        Response put2 = rt.put(0, 0, 0, "b");
        assertEquals(INSERTED, put2.status);

        assertEquals(put1.id, rt.reserve().id);
        assertEquals(put2.id, rt.reserve().id);

        assertEquals(TIMED_OUT, rt.reserve().status);
    }

    @Test
    public void testPutPriorityReserve2() throws Exception {
        RTalk rt = new RTalk(jedisPool);
        Response put1 = rt.put(1, 0, 0, "a");
        assertEquals(INSERTED, put1.status);

        Response put2 = rt.put(0, 0, 0, "b");
        assertEquals(INSERTED, put2.status);

        assertEquals(put2.id, rt.reserve().id);
        assertEquals(put1.id, rt.reserve().id);

        assertEquals(TIMED_OUT, rt.reserve().status);
    }

    @Test
    public void testPutDelayReserve() throws Exception {
        RTalk rt = new RTalk(jedisPool);
        String expectedData = "{hello: 'world'}";
        Response put = rt.put(0, 1000, 0, expectedData);
        assertEquals(INSERTED, put.status);
        assertTrue(isNotBlank(put.id));
    }

    @Test
    public void testPutBuryKickJob() throws Exception {
        RTalk rt = new RTalk(jedisPool);
        Response put = rt.put(0, 0, 0, "a");
        assertEquals(INSERTED, put.status);
        assertEquals(BURIED, rt.bury(put.id, 0).status);
        assertEquals(0, rt.statsTube().currentjobsready);
        assertEquals(1, rt.statsTube().currentjobsburied);
        assertEquals(KICKED, rt.kickJob(put.id).status);
        assertEquals(0, rt.statsTube().currentjobsburied);
        assertEquals(1, rt.statsTube().currentjobsready);
        Response reserve = rt.reserve();
        assertEquals(RESERVED, reserve.status);
        assertEquals(put.id, reserve.id);
    }

    @Test
    public void testKickJobNotFound() throws Exception {
        RTalk rt = new RTalk(jedisPool);
        Response put = rt.put(0, 0, 0, "a");
        assertEquals(INSERTED, put.status);
        assertEquals(NOT_FOUND, rt.kickJob(put.id).status);
    }

    @Test
    public void testTouch() throws Exception {
        RTalk rt = new RTalk(jedisPool);
        Response put = rt.put(0, 0, 42000, "a");
        Job statsJob = rt.statsJob(put.id);
        long readyTime1 = statsJob.readyTime;
        Response touch = rt.touch(put.id);
        assertEquals(RTalk.TOUCHED, touch.status);
        Job statsJob2 = rt.statsJob(put.id);
        long readyTime2 = statsJob2.readyTime;
        assertTrue(readyTime1 < readyTime2);
        assertEquals(42000, readyTime2 - readyTime1);
    }

}
