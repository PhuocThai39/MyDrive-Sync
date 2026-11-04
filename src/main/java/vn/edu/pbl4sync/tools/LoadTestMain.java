package vn.edu.pbl4sync.tools;

import vn.edu.pbl4sync.client.NetworkClient;
import vn.edu.pbl4sync.common.Packet;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static vn.edu.pbl4sync.common.MessageTypes.*;

/**
 * Lightweight connection test (not a file-transfer benchmark).
 * Usage: LoadTestMain host port username password connections [holdSeconds]
 */
public class LoadTestMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("Usage: LoadTestMain host port username password connections [holdSeconds]");
            return;
        }
        String host = args[0], user = args[2], pass = args[3];
        int port = Integer.parseInt(args[1]), n = Integer.parseInt(args[4]);
        int hold = args.length > 5 ? Integer.parseInt(args[5]) : 30;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 100));
        List<NetworkClient> clients = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(n);
        long start = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    NetworkClient c = new NetworkClient();
                    c.connect(host, port);
                    Packet r = c.request(Packet.request(LOGIN_REQUEST).with("username", user).with("password", pass)
                            .with("deviceName", "FakeAgent-" + id), Duration.ofSeconds(10));
                    if (r.getBoolean("success", false)) {
                        clients.add(c);
                        c.request(Packet.request(DASHBOARD_REQUEST), Duration.ofSeconds(10));
                    } else
                        c.close();
                } catch (Exception e) {
                    System.err.println("Agent " + id + " failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        long elapsed = System.currentTimeMillis() - start;
        System.out
                .println("Connected: " + clients.size() + "/" + n + " in " + elapsed + " ms. Holding " + hold + "s...");
        Thread.sleep(hold * 1000L);
        for (NetworkClient c : clients)
            c.close();
        pool.shutdownNow();
        System.out.println("Done.");
    }
}
