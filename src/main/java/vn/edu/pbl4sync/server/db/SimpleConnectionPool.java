package vn.edu.pbl4sync.server.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class SimpleConnectionPool implements AutoCloseable {
    private final BlockingQueue<Connection> pool;

    public SimpleConnectionPool(String url, String user, String password, int size, Properties connectionProperties)
            throws SQLException {
        this.pool = new ArrayBlockingQueue<>(size);
        try {
            for (int i = 0; i < size; i++) {
                Properties properties = new Properties();
                properties.putAll(connectionProperties);
                properties.setProperty("user", user);
                properties.setProperty("password", password);
                pool.add(DriverManager.getConnection(url, properties));
            }
        } catch (SQLException e) {
            close();
            throw e;
        }
    }

    public Connection borrow() throws SQLException {
        try {
            Connection c = pool.poll(10, TimeUnit.SECONDS);
            if (c == null) throw new SQLException("Timed out waiting for a database connection");
            if (c.isClosed() || !c.isValid(2)) throw new SQLException("Database connection is not valid");
            return c;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted waiting for database connection", e);
        }
    }

    public void release(Connection c) {
        if (c == null) return;
        try {
            if (!c.isClosed()) pool.offer(c);
        } catch (SQLException ignored) { }
    }

    @Override
    public void close() {
        for (Connection c : pool) {
            try { c.close(); } catch (SQLException ignored) { }
        }
        pool.clear();
    }
}
