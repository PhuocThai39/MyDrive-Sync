package vn.edu.pbl4sync.common;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TlsSupport {
    private static final String[] SUPPORTED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private TlsSupport() {}

    public static SSLContext serverContext() throws IOException, GeneralSecurityException {
        Path keyStorePath = Path.of(environment("PBL4SYNC_TLS_KEYSTORE", "certs/server.p12"));
        char[] storePassword = requiredPassword("PBL4SYNC_TLS_KEYSTORE_PASSWORD");
        char[] keyPassword = optionalPassword("PBL4SYNC_TLS_KEY_PASSWORD", storePassword);
        try {
            KeyStore keyStore = loadKeyStore(keyStorePath, storePassword);
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, keyPassword);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);
            return context;
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
        }
    }

    public static SSLContext clientContext() throws IOException, GeneralSecurityException {
        Path trustStorePath = Path.of(environment("PBL4SYNC_TLS_TRUSTSTORE", "certs/server-truststore.p12"));
        char[] trustStorePassword = requiredPassword("PBL4SYNC_TLS_TRUSTSTORE_PASSWORD");
        try {
            KeyStore trustStore = loadKeyStore(trustStorePath, trustStorePassword);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        } finally {
            Arrays.fill(trustStorePassword, '\0');
        }
    }

    public static void configureServerSocket(SSLServerSocket socket) throws IOException {
        socket.setEnabledProtocols(enabledProtocols(socket.getSupportedProtocols()));
    }

    public static void configureServerSocket(SSLSocket socket) throws IOException {
        socket.setEnabledProtocols(enabledProtocols(socket.getSupportedProtocols()));
    }

    public static void configureClientSocket(SSLSocket socket) throws IOException {
        socket.setEnabledProtocols(enabledProtocols(socket.getSupportedProtocols()));
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
    }

    private static String[] enabledProtocols(String[] supported) throws IOException {
        List<String> enabled = new ArrayList<>();
        for (String protocol : SUPPORTED_PROTOCOLS) {
            for (String candidate : supported) {
                if (protocol.equals(candidate)) {
                    enabled.add(protocol);
                    break;
                }
            }
        }
        if (enabled.isEmpty()) throw new IOException("TLS 1.2 or newer is required");
        return enabled.toArray(String[]::new);
    }

    private static KeyStore loadKeyStore(Path path, char[] password)
            throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(path)) throw new IOException("TLS store does not exist: " + path);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path)) {
            keyStore.load(input, password);
        }
        return keyStore;
    }

    private static char[] requiredPassword(String name) throws IOException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Required TLS password environment variable is not set: " + name);
        }
        return value.toCharArray();
    }

    private static char[] optionalPassword(String name, char[] fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback.clone() : value.toCharArray();
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
