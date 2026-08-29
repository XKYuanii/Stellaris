import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

public final class BloomFilterSeeder {
    private BloomFilterSeeder() {
    }

    public static void main(String[] args) {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected redisHost redisPort redisPassword bloomName programId");
        }
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + args[0] + ":" + args[1])
                .setDatabase(0)
                .setPassword(args[2]);
        RedissonClient client = Redisson.create(config);
        try {
            RBloomFilter<String> filter = client.getBloomFilter(args[3]);
            filter.add(args[4]);
            System.out.println("programId=" + args[4] + " contains=" + filter.contains(args[4]));
        } finally {
            client.shutdown();
        }
    }
}
