/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Runs the unpacked distribution with an isolated home and temporary directory, and with network
 * access denied except to the test databases.
 *
 * <p>The archive targets the build host's platform, so on macOS the launcher runs on the host under
 * a {@code sandbox-exec} profile that allows only the database ports. On Linux it runs in a
 * Java-only container attached to an internal Docker network that contains just the databases.
 */
sealed interface RestrictedEnvironment extends AutoCloseable {
  String DATABASE_IMAGE = "postgres:16.4";
  int DATABASE_PORT = 5432;

  /** A PostgreSQL database and the address at which the distribution reaches it. */
  record Database(GenericContainer<?> container, String password, String host, int port) {
    String jdbcUrl() {
      return "jdbc:postgresql://" + host + ":" + port + "/postgres";
    }

    void execute(String sql) throws IOException, InterruptedException {
      var result =
          container.execInContainer(
              "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "postgres", "-c", sql);
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
  }

  /** The outcome of one CLI invocation. */
  record Result(int exitCode, String standardOutput, String standardError) {
    String allOutput() {
      return standardOutput + standardError;
    }
  }

  Database store();

  Database source();

  /** A workspace path as the distribution sees it. */
  String workspace(String name);

  /**
   * Runs the launcher with only the given variables in addition to the isolated home, temporary
   * directory, and Java runtime.
   */
  Result run(Map<String, String> environment, String... arguments) throws Exception;

  @Override
  void close();

  static RestrictedEnvironment start(String platform, Path distribution, Path scratch)
      throws Exception {
    return platform.startsWith("osx_")
        ? HostSandbox.start(distribution, scratch)
        : InternalNetworkContainer.start(distribution);
  }

  private static GenericContainer<?> database(String password) {
    return new GenericContainer<>(DATABASE_IMAGE)
        .withEnv("POSTGRES_PASSWORD", password)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
  }

  private static String password() {
    return "pg-" + UUID.randomUUID();
  }

  /** Runs the launcher on the macOS host under a network-restricting sandbox profile. */
  final class HostSandbox implements RestrictedEnvironment {
    private static final String LOOPBACK = "127.0.0.1";

    private final GenericContainer<?> storeContainer;
    private final GenericContainer<?> sourceContainer;
    private final Database store;
    private final Database source;
    private final Path launcher;
    private final Path home;
    private final Path temporary;
    private final Path workspaces;
    private final String javaHome;
    private final String profile;

    private HostSandbox(
        GenericContainer<?> storeContainer,
        String storePassword,
        GenericContainer<?> sourceContainer,
        String sourcePassword,
        Path distribution,
        Path scratch)
        throws IOException {
      this.storeContainer = storeContainer;
      this.sourceContainer = sourceContainer;
      // Docker publishes the database ports on the loopback interface of the local host.
      assertThat(storeContainer.getHost()).isIn("localhost", LOOPBACK);
      store =
          new Database(
              storeContainer, storePassword, LOOPBACK, storeContainer.getMappedPort(DATABASE_PORT));
      source =
          new Database(
              sourceContainer,
              sourcePassword,
              LOOPBACK,
              sourceContainer.getMappedPort(DATABASE_PORT));
      launcher = distribution.resolve("bin/scalardb-analytics");
      home = Files.createDirectories(scratch.resolve("home"));
      temporary = Files.createDirectories(scratch.resolve("tmp"));
      workspaces = Files.createDirectories(scratch.resolve("workspaces"));
      javaHome = DistributionSmokeTest.requiredProperty("distribution.javaHome");
      profile = profile(List.of(store.port(), source.port()));
    }

    static HostSandbox start(Path distribution, Path scratch) throws Exception {
      var storePassword = password();
      var sourcePassword = password();
      var storeContainer = database(storePassword).withExposedPorts(DATABASE_PORT);
      var sourceContainer = database(sourcePassword).withExposedPorts(DATABASE_PORT);
      storeContainer.start();
      sourceContainer.start();
      var sandbox =
          new HostSandbox(
              storeContainer,
              storePassword,
              sourceContainer,
              sourcePassword,
              distribution,
              scratch);
      sandbox.verifyNetworkIsRestricted();
      return sandbox;
    }

    /**
     * Denies all network access, including name resolution, except outbound connections to the
     * given database ports. The JVM connects to 127.0.0.1 through IPv4-mapped IPv6 sockets, which
     * the profile's {@code localhost} host does not match, so the ports are allowed on any host;
     * the ports are the random host ports Docker published for the test databases.
     */
    private static String profile(List<Integer> allowedPorts) {
      var allowed =
          allowedPorts.stream()
              .map(port -> "(remote ip \"*:" + port + "\")")
              .collect(Collectors.joining(" "));
      return "(version 1)\n"
          + "(allow default)\n"
          + "(deny network*)\n"
          + "(allow network-outbound "
          + allowed
          + ")\n";
    }

    /** Fails unless the profile allows the databases and denies another local listener. */
    private void verifyNetworkIsRestricted() throws Exception {
      try (var listener = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
        var denied = probe(listener.getLocalPort());
        var allowed = probe(store.port());
        assertThat(allowed.exitCode()).as("database port allowed: %s", allowed).isZero();
        assertThat(denied.exitCode()).as("other port denied: %s", denied).isNotZero();
      }
    }

    private Result probe(int port) throws Exception {
      return execute(
          List.of(
              "/usr/bin/sandbox-exec",
              "-p",
              profile,
              "/usr/bin/nc",
              "-z",
              "-w",
              "5",
              LOOPBACK,
              Integer.toString(port)),
          Map.of("PATH", "/usr/bin:/bin"));
    }

    @Override
    public Database store() {
      return store;
    }

    @Override
    public Database source() {
      return source;
    }

    @Override
    public String workspace(String name) {
      return workspaces.resolve(name).toString();
    }

    @Override
    public Result run(Map<String, String> environment, String... arguments) throws Exception {
      var command = new ArrayList<String>();
      command.addAll(List.of("/usr/bin/sandbox-exec", "-p", profile, launcher.toString()));
      command.addAll(List.of(arguments));
      var variables = new LinkedHashMap<String, String>();
      variables.put("PATH", "/usr/bin:/bin");
      variables.put("JAVA_HOME", javaHome);
      variables.put("HOME", home.toString());
      variables.put("TMPDIR", temporary.toString());
      // The JVM takes its home and temporary directories from system properties, not from the
      // environment variables that native libraries read.
      variables.put("JAVA_OPTS", "-Duser.home=" + home + " -Djava.io.tmpdir=" + temporary);
      variables.putAll(environment);
      return execute(command, variables);
    }

    private Result execute(List<String> command, Map<String, String> environment) throws Exception {
      var id = UUID.randomUUID();
      var output = temporary.resolve(id + ".out");
      var error = temporary.resolve(id + ".err");
      var builder = new ProcessBuilder(command).redirectOutput(output.toFile());
      builder
          .redirectError(error.toFile())
          .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
      builder.environment().clear();
      builder.environment().putAll(environment);
      var process = builder.start();
      if (!process.waitFor(3, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new AssertionError("Timed out: " + command);
      }
      try {
        return new Result(
            process.exitValue(),
            Files.readString(output, StandardCharsets.UTF_8),
            Files.readString(error, StandardCharsets.UTF_8));
      } finally {
        Files.deleteIfExists(output);
        Files.deleteIfExists(error);
      }
    }

    @Override
    public void close() {
      sourceContainer.stop();
      storeContainer.stop();
    }
  }

  /** Runs the launcher in a Java-only container on an internal network with the databases. */
  final class InternalNetworkContainer implements RestrictedEnvironment {
    private static final String RUNTIME_IMAGE = "eclipse-temurin:25-jre";
    private static final String DISTRIBUTION = "/opt/scalardb-analytics";
    private static final String HOME = "/isolated/home";
    private static final String TEMPORARY = "/isolated/tmp";
    private static final String JAVA_HOME = "/opt/java/openjdk";

    private final Network network;
    private final GenericContainer<?> storeContainer;
    private final GenericContainer<?> sourceContainer;
    private final GenericContainer<?> runtime;
    private final Database store;
    private final Database source;

    private InternalNetworkContainer(
        Network network,
        GenericContainer<?> storeContainer,
        String storePassword,
        GenericContainer<?> sourceContainer,
        String sourcePassword,
        GenericContainer<?> runtime) {
      this.network = network;
      this.storeContainer = storeContainer;
      this.sourceContainer = sourceContainer;
      this.runtime = runtime;
      store = new Database(storeContainer, storePassword, "store", DATABASE_PORT);
      source = new Database(sourceContainer, sourcePassword, "source", DATABASE_PORT);
    }

    static InternalNetworkContainer start(Path distribution) throws Exception {
      // An internal network has no route outside Docker, so only its members are reachable.
      var network =
          Network.builder().createNetworkCmdModifier(command -> command.withInternal(true)).build();
      var storePassword = password();
      var sourcePassword = password();
      var storeContainer = database(storePassword).withNetwork(network).withNetworkAliases("store");
      var sourceContainer =
          database(sourcePassword).withNetwork(network).withNetworkAliases("source");
      var runtime =
          new GenericContainer<>(RUNTIME_IMAGE)
              .withNetwork(network)
              .withFileSystemBind(distribution.toString(), DISTRIBUTION, BindMode.READ_ONLY)
              .withCommand("sleep", "infinity");
      storeContainer.start();
      sourceContainer.start();
      runtime.start();
      var created = runtime.execInContainer("mkdir", "-p", HOME, TEMPORARY, "/workspaces");
      assertThat(created.getExitCode()).as(created.getStderr()).isZero();
      return new InternalNetworkContainer(
          network, storeContainer, storePassword, sourceContainer, sourcePassword, runtime);
    }

    @Override
    public Database store() {
      return store;
    }

    @Override
    public Database source() {
      return source;
    }

    @Override
    public String workspace(String name) {
      return "/workspaces/" + name;
    }

    @Override
    public Result run(Map<String, String> environment, String... arguments) throws Exception {
      var command = new ArrayList<String>();
      command.addAll(
          List.of(
              "env",
              "-i",
              "PATH=/usr/bin:/bin",
              "JAVA_HOME=" + JAVA_HOME,
              "HOME=" + HOME,
              "TMPDIR=" + TEMPORARY,
              "JAVA_OPTS=-Duser.home=" + HOME + " -Djava.io.tmpdir=" + TEMPORARY));
      environment.forEach((name, value) -> command.add(name + "=" + value));
      command.add(DISTRIBUTION + "/bin/scalardb-analytics");
      command.addAll(List.of(arguments));
      var result = runtime.execInContainer(command.toArray(String[]::new));
      return new Result(result.getExitCode(), result.getStdout(), result.getStderr());
    }

    @Override
    public void close() {
      runtime.stop();
      sourceContainer.stop();
      storeContainer.stop();
      network.close();
    }
  }
}
