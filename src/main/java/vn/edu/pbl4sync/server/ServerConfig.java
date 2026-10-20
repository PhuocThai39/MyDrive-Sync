package vn.edu.pbl4sync.server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public record ServerConfig(int port, String dbUrl, String dbUser, String dbPassword, String initialAdminPassword,
                           int dbPoolSize, int clientThreads, int transferThreads, String dbSslMode) {
    public static ServerConfig load(Path path) {
        Properties p = new Properties();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) { p.load(in); } catch (Exception ignored) { }
        }
        return new ServerConfig(
                Integer.parseInt(p.getProperty("server.port", "5000")),
                p.getProperty("db.url", "jdbc:mysql://localhost:3306/pbl4sync?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh"),
                p.getProperty("db.user", "root"),
                p.getProperty("db.password", "123456"),
                p.getProperty("admin.initialPassword", "admin123"),
                Integer.parseInt(p.getProperty("db.poolSize", "10")),
                Integer.parseInt(p.getProperty("server.clientThreads", "150")),
                Integer.parseInt(p.getProperty("server.transferThreads", "10")),
                p.getProperty("db.sslMode", "DISABLED").trim().toUpperCase(Locale.ROOT)
        );
    }

    public Properties dbConnectionProperties() {
        if (!switch (dbSslMode) {
            case "DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY" -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException("Unsupported db.sslMode: " + dbSslMode);
        }

        Properties properties = new Properties();
        properties.setProperty("sslMode", dbSslMode);
        if ("VERIFY_CA".equals(dbSslMode) || "VERIFY_IDENTITY".equals(dbSslMode)) {
            String trustStorePath = environment("PBL4SYNC_DB_TRUSTSTORE", "certs/mysql-truststore.p12");
            String trustStorePassword = System.getenv("PBL4SYNC_DB_TRUSTSTORE_PASSWORD");
            if (trustStorePassword == null || trustStorePassword.isBlank()) {
                throw new IllegalStateException("PBL4SYNC_DB_TRUSTSTORE_PASSWORD is required for db.sslMode=" + dbSslMode);
            }
            Path trustStore = Path.of(trustStorePath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(trustStore)) {
                throw new IllegalStateException("MySQL TLS truststore does not exist: " + trustStore);
            }
            properties.setProperty("trustCertificateKeyStoreUrl", trustStore.toUri().toString());
            properties.setProperty("trustCertificateKeyStoreType", "PKCS12");
            properties.setProperty("trustCertificateKeyStorePassword", trustStorePassword);
        }
        return properties;
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
