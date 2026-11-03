package com.afterbooth.global.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class LoopbackTls {
  private static final char[] PASSWORD;
  private static final Path STORE;

  static {
    try {
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      PASSWORD = Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray();
      STORE = Files.createTempDirectory("afterbooth-test-tls-").resolve("loopback.p12");
      Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                  "-genkeypair",
                  "-alias",
                  "loopback",
                  "-keyalg",
                  "RSA",
                  "-keysize",
                  "2048",
                  "-validity",
                  "2",
                  "-dname",
                  "CN=localhost",
                  "-ext",
                  "SAN=dns:localhost,ip:127.0.0.1",
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  STORE.toString(),
                  "-storepass",
                  String.valueOf(PASSWORD),
                  "-keypass",
                  String.valueOf(PASSWORD),
                  "-noprompt")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (process.waitFor() != 0)
        throw new IllegalStateException("Synthetic TLS generation failed");
      STORE.toFile().deleteOnExit();
      STORE.getParent().toFile().deleteOnExit();
    } catch (Exception e) {
      throw new IllegalStateException("Synthetic TLS setup failed");
    }
  }

  private LoopbackTls() {}

  public static void configure(DynamicPropertyRegistry registry) {
    registry.add("server.ssl.enabled", () -> true);
    registry.add("server.ssl.key-store", () -> STORE.toUri().toString());
    registry.add("server.ssl.key-store-type", () -> "PKCS12");
    registry.add("server.ssl.key-store-password", () -> String.valueOf(PASSWORD));
  }

  public static SSLContext clientContext() throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(STORE)) {
      store.load(input, PASSWORD);
    }
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, factory.getTrustManagers(), new SecureRandom());
    return context;
  }
}
