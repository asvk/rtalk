package rtalk;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisException;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class RedisDao {
    protected final JedisPool pool;

    public RedisDao(JedisPool pool) {
        this.pool = pool;
    }

    protected long getDb() {
        return withRedis(r -> r.getClient()
                               .getDB());
    }

    protected <T> T withRedis(Function<Jedis, T> r) {
        Jedis redis = getRedis();
        try {
            return r.apply(redis);
        } finally {
            redis.close();
        }
    }

    protected void updateRedis(Consumer<Jedis> r) {
        Jedis redis = getRedis();
        try {
            r.accept(redis);
        } finally {
            redis.close();
        }
    }

    protected Jedis getRedis() {
        return pool.getResource();
    }

    protected void updateRedisTransaction(Consumer<Transaction> r) {
        withRedisTransaction(r, (Runnable) null);
    }
    
    protected <T> T withRedisTransaction(Function<Transaction, T> r) {
        Jedis redis = getRedis();
        Transaction transaction = null;
        try {
            transaction = redis.multi();
            T retval = r.apply(transaction);
            List<Object> results = transaction.exec();
            transaction = null;
            checkResults(results);
            return retval;
        } finally {
            rollback(transaction);
            redis.close();
        }
    }

    protected void withRedisTransaction(Consumer<Transaction> r, Runnable onOk) {
        Jedis redis = getRedis();
        Transaction transaction = null;
        try {
            transaction = redis.multi();
            r.accept(transaction);
            List<Object> results = transaction.exec();
            transaction = null;
            checkResults(results);
            if (onOk != null) {
                onOk.run();
            }
        } finally {
            rollback(transaction);
            redis.close();
        }
    }

    private void checkResults(List<Object> results) {
        results.stream().filter(x -> !(x instanceof String)).forEach(x -> {
            JedisException exception = (JedisException) x;
            throw exception;
        });
    }

    protected void withRedisTransaction(Consumer<Transaction> r, Consumer<Jedis> onOk) {
        Jedis redis = getRedis();
        Transaction transaction = null;
        try {
            transaction = redis.multi();
            r.accept(transaction);
            List<Object> results = transaction.exec();
            transaction = null;
            checkResults(results);
            if (onOk != null) {
                onOk.accept(redis);
            }
        } finally {
            rollback(transaction);
            redis.close();
        }
    }

    private void rollback(Transaction transaction) {
        if (transaction != null) {
            transaction.discard();
        }
    }

}
