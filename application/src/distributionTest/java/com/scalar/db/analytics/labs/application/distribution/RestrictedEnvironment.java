/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.command.InspectExecResponse;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;

/**
 * Runs the unpacked distribution with an isolated home and temporary directory, and with network
 * access denied except to the test databases: a PostgreSQL catalog store and the named source
 * servers.
 *
 * <p>The archive targets the build host's platform, so on macOS the launcher runs on the host under
 * a {@code sandbox-exec} profile that allows only the database ports. On Linux it runs in a
 * Java-only container attached to an internal Docker network that contains just the databases.
 *
 * <p>Paths taken and returned by this interface are paths as the distribution sees them.
 */
sealed interface RestrictedEnvironment extends AutoCloseable {
  Duration COMMAND_TIMEOUT = Duration.ofMinutes(3);

  /** The kind of a source server. */
  enum ServerType {
    POSTGRESQL,
    MYSQL
  }

  /** The outcome of one CLI invocation. */
  record Result(int exitCode, String standardOutput, String standardError) {
    String allOutput() {
      return standardOutput + standardError;
    }
  }

  /** A launcher invocation that has started and may still be running. */
  interface Running {
    /** Sends SIGINT to the launched process. */
    void interrupt() throws Exception;

    /** Waits for the process to exit, failing if it does not exit within the timeout. */
    Result await(Duration timeout) throws Exception;
  }

  DatabaseServer.PostgreSql store();

  /** The source server started under the given name. */
  DatabaseServer server(String name);

  /** A workspace path. */
  String workspace(String name);

  /** The isolated home directory. */
  String home();

  /** The isolated temporary directory. */
  String temporary();

  /** Writes a file, creating its parent directories. */
  void writeFile(String path, String content) throws Exception;

  /** The regular files under a directory, by path relative to it, with their contents. */
  Map<String, String> files(String directory) throws Exception;

  /** The distribution's launcher, by its path in the distribution. */
  String LAUNCHER = "bin/scalardb-analytics";

  /**
   * Starts an executable of the distribution, named by its path in the distribution, with only the
   * given variables in addition to the isolated home, temporary directory, and Java runtime,
   * reading standard input from the given text or from an empty input.
   */
  Running startExecutable(
      String executable,
      Map<String, String> environment,
      @Nullable String standardInput,
      String... arguments)
      throws Exception;

  /** Starts the launcher; see {@link #startExecutable}. */
  default Running start(
      Map<String, String> environment, @Nullable String standardInput, String... arguments)
      throws Exception {
    return startExecutable(LAUNCHER, environment, standardInput, arguments);
  }

  /** Runs the launcher to completion; see {@link #start}. */
  default Result runWithInput(
      Map<String, String> environment, @Nullable String standardInput, String... arguments)
      throws Exception {
    return start(environment, standardInput, arguments).await(COMMAND_TIMEOUT);
  }

  /** Runs the launcher to completion with an empty standard input. */
  default Result run(Map<String, String> environment, String... arguments) throws Exception {
    return runWithInput(environment, null, arguments);
  }

  @Override
  void close();

  static RestrictedEnvironment start(
      String platform, Path distribution, Path scratch, Map<String, ServerType> sources)
      throws Exception {
    return platform.startsWith("osx_")
        ? HostSandbox.start(distribution, scratch, sources)
        : InternalNetworkContainer.start(distribution, sources);
  }

  /** A server container that has not started, with its administrator password. */
  record PendingServer(ServerType type, GenericContainer<?> container, String password) {
    static PendingServer create(ServerType type) {
      var password = DatabaseServer.password(type.name().toLowerCase());
      var container =
          switch (type) {
            case POSTGRESQL -> DatabaseServer.PostgreSql.container(password);
            case MYSQL -> DatabaseServer.MySql.container(password);
          };
      return new PendingServer(type, container, password);
    }

    int internalPort() {
      return switch (type) {
        case POSTGRESQL -> DatabaseServer.PostgreSql.PORT;
        case MYSQL -> DatabaseServer.MySql.PORT;
      };
    }

    DatabaseServer reachedAt(String host, int port) {
      return switch (type) {
        case POSTGRESQL -> new DatabaseServer.PostgreSql(container, password, host, port);
        case MYSQL -> new DatabaseServer.MySql(container, password, host, port);
      };
    }
  }

  private static Map<String, PendingServer> pending(Map<String, ServerType> sources) {
    var servers = new LinkedHashMap<String, PendingServer>();
    sources.forEach((name, type) -> servers.put(name, PendingServer.create(type)));
    return servers;
  }

  private static <T> Map<String, T> mapServers(
      Map<String, PendingServer> servers, BiFunction<String, PendingServer, T> function) {
    var result = new LinkedHashMap<String, T>();
    servers.forEach((name, server) -> result.put(name, function.apply(name, server)));
    return result;
  }

  private static <T> T lookup(Map<String, T> servers, String name) {
    var server = servers.get(name);
    if (server == null) {
      throw new IllegalArgumentException("No source server named " + name);
    }
    return server;
  }

  /** Runs the launcher on the macOS host under a network-restricting sandbox profile. */
  final class HostSandbox implements RestrictedEnvironment {
    private static final String LOOPBACK = "127.0.0.1";

    private final DatabaseServer.PostgreSql store;
    private final Map<String, DatabaseServer> sources;
    private final Path distribution;
    private final Path home;
    private final Path temporary;
    private final Path workspaces;
    private final Path captures;
    private final String javaHome;
    private final String profile;

    private HostSandbox(
        DatabaseServer.PostgreSql store,
        Map<String, DatabaseServer> sources,
        Path distribution,
        Path scratch)
        throws IOException {
      this.store = store;
      this.sources = sources;
      this.distribution = distribution;
      home = Files.createDirectories(scratch.resolve("home"));
      temporary = Files.createDirectories(scratch.resolve("tmp"));
      workspaces = Files.createDirectories(scratch.resolve("workspaces"));
      captures = Files.createDirectories(scratch.resolve("captures"));
      javaHome = DistributionSmokeTest.requiredProperty("distribution.javaHome");
      var ports = new ArrayList<Integer>();
      ports.add(store.port());
      sources.values().forEach(source -> ports.add(source.port()));
      profile = profile(ports);
    }

    static HostSandbox start(Path distribution, Path scratch, Map<String, ServerType> sources)
        throws Exception {
      var storeServer = PendingServer.create(ServerType.POSTGRESQL);
      var sourceServers = pending(sources);
      storeServer.container().withExposedPorts(storeServer.internalPort()).start();
      for (var server : sourceServers.values()) {
        server.container().withExposedPorts(server.internalPort()).start();
      }
      // Docker publishes the database ports on the loopback interface of the local host.
      assertThat(storeServer.container().getHost()).isIn("localhost", LOOPBACK);
      var sandbox =
          new HostSandbox(
              (DatabaseServer.PostgreSql) published(storeServer),
              mapServers(sourceServers, (name, server) -> published(server)),
              distribution,
              scratch);
      sandbox.verifyNetworkIsRestricted();
      return sandbox;
    }

    private static DatabaseServer published(PendingServer server) {
      return server.reachedAt(LOOPBACK, server.container().getMappedPort(server.internalPort()));
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
      return launch(
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
              Map.of("PATH", "/usr/bin:/bin"),
              null)
          .await(COMMAND_TIMEOUT);
    }

    @Override
    public DatabaseServer.PostgreSql store() {
      return store;
    }

    @Override
    public DatabaseServer server(String name) {
      return lookup(sources, name);
    }

    @Override
    public String workspace(String name) {
      return workspaces.resolve(name).toString();
    }

    @Override
    public String home() {
      return home.toString();
    }

    @Override
    public String temporary() {
      return temporary.toString();
    }

    @Override
    public void writeFile(String path, String content) throws IOException {
      var file = Path.of(path);
      Files.createDirectories(Objects.requireNonNull(file.getParent(), path));
      Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Override
    public Map<String, String> files(String directory) throws IOException {
      var root = Path.of(directory);
      var files = new TreeMap<String, String>();
      if (!Files.exists(root)) {
        return files;
      }
      try (var paths = Files.walk(root)) {
        for (var file : paths.filter(Files::isRegularFile).toList()) {
          files.put(
              root.relativize(file).toString(),
              new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
      }
      return files;
    }

    @Override
    public Running startExecutable(
        String executable,
        Map<String, String> environment,
        @Nullable String standardInput,
        String... arguments)
        throws Exception {
      var command = new ArrayList<String>();
      command.addAll(
          List.of(
              "/usr/bin/sandbox-exec", "-p", profile, distribution.resolve(executable).toString()));
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
      return launch(command, variables, standardInput);
    }

    private Running launch(
        List<String> command, Map<String, String> environment, @Nullable String standardInput)
        throws IOException {
      var id = UUID.randomUUID();
      var output = captures.resolve(id + ".out");
      var error = captures.resolve(id + ".err");
      var builder =
          new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile());
      if (standardInput == null) {
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
      } else {
        var input = captures.resolve(id + ".in");
        Files.writeString(input, standardInput, StandardCharsets.UTF_8);
        builder.redirectInput(input.toFile());
      }
      builder.environment().clear();
      builder.environment().putAll(environment);
      // sandbox-exec and the launcher script each exec their command, so this process becomes the
      // JVM and receives the signals sent to it.
      var process = builder.start();
      return new Running() {
        @Override
        public void interrupt() throws Exception {
          var kill = new ProcessBuilder("kill", "-INT", Long.toString(process.pid())).start();
          assertThat(kill.waitFor(10, TimeUnit.SECONDS) && kill.exitValue() == 0).isTrue();
        }

        @Override
        public Result await(Duration timeout) throws Exception {
          if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
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
            Files.deleteIfExists(captures.resolve(id + ".in"));
          }
        }
      };
    }

    @Override
    public void close() {
      sources.values().forEach(source -> source.container().stop());
      store.container().stop();
    }
  }

  /** Runs the launcher in a Java-only container on an internal network with the databases. */
  final class InternalNetworkContainer implements RestrictedEnvironment {
    private static final String RUNTIME_IMAGE = "eclipse-temurin:25-jre";
    private static final String DISTRIBUTION = "/opt/scalardb-analytics";
    private static final String HOME = "/isolated/home";
    private static final String TEMPORARY = "/isolated/tmp";
    private static final String CAPTURES = "/captures";
    private static final String JAVA_HOME = "/opt/java/openjdk";
    private static final String STORE_ALIAS = "store";

    private final Network network;
    private final GenericContainer<?> runtime;
    private final DatabaseServer.PostgreSql store;
    private final Map<String, DatabaseServer> sources;

    private InternalNetworkContainer(
        Network network,
        GenericContainer<?> runtime,
        DatabaseServer.PostgreSql store,
        Map<String, DatabaseServer> sources) {
      this.network = network;
      this.runtime = runtime;
      this.store = store;
      this.sources = sources;
    }

    static InternalNetworkContainer start(Path distribution, Map<String, ServerType> sources)
        throws Exception {
      // An internal network has no route outside Docker, so only its members are reachable.
      var network =
          Network.builder().createNetworkCmdModifier(command -> command.withInternal(true)).build();
      var storeServer = PendingServer.create(ServerType.POSTGRESQL);
      var sourceServers = pending(sources);
      storeServer.container().withNetwork(network).withNetworkAliases(STORE_ALIAS).start();
      sourceServers.forEach(
          (name, server) ->
              server.container().withNetwork(network).withNetworkAliases(sourceAlias(name)));
      sourceServers.values().forEach(server -> server.container().start());
      var runtime =
          new GenericContainer<>(RUNTIME_IMAGE)
              .withNetwork(network)
              .withFileSystemBind(distribution.toString(), DISTRIBUTION, BindMode.READ_ONLY)
              .withCommand("sleep", "infinity");
      runtime.start();
      var created =
          runtime.execInContainer("mkdir", "-p", HOME, TEMPORARY, CAPTURES, "/workspaces");
      assertThat(created.getExitCode()).as(created.getStderr()).isZero();
      return new InternalNetworkContainer(
          network,
          runtime,
          (DatabaseServer.PostgreSql)
              storeServer.reachedAt(STORE_ALIAS, storeServer.internalPort()),
          mapServers(
              sourceServers,
              (name, server) -> server.reachedAt(sourceAlias(name), server.internalPort())));
    }

    // A server's host name differs from its source name, so a check that output never contains
    // the host is not defeated by output that names the source.
    private static String sourceAlias(String name) {
      return name + "-server";
    }

    @Override
    public DatabaseServer.PostgreSql store() {
      return store;
    }

    @Override
    public DatabaseServer server(String name) {
      return lookup(sources, name);
    }

    @Override
    public String workspace(String name) {
      return "/workspaces/" + name;
    }

    @Override
    public String home() {
      return HOME;
    }

    @Override
    public String temporary() {
      return TEMPORARY;
    }

    @Override
    public void writeFile(String path, String content) throws Exception {
      var parent = Objects.requireNonNull(Path.of(path).getParent(), path).toString();
      shell("mkdir -p " + quote(parent));
      runtime.copyFileToContainer(Transferable.of(content.getBytes(StandardCharsets.UTF_8)), path);
    }

    @Override
    public Map<String, String> files(String directory) throws Exception {
      var files = new TreeMap<String, String>();
      var listing =
          shell(
              "if [ -d "
                  + quote(directory)
                  + " ]; then cd "
                  + quote(directory)
                  + " && find . -type f; fi");
      for (var file : listing.lines().filter(line -> !line.isBlank()).toList()) {
        var relative = file.substring(2);
        files.put(relative, shell("cat " + quote(directory + "/" + relative)));
      }
      return files;
    }

    @Override
    public Running startExecutable(
        String executable,
        Map<String, String> environment,
        @Nullable String standardInput,
        String... arguments)
        throws Exception {
      var id = UUID.randomUUID().toString();
      var base = CAPTURES + "/" + id;
      var input = "/dev/null";
      if (standardInput != null) {
        input = base + ".in";
        writeFile(input, standardInput);
      }
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
      command.add(DISTRIBUTION + "/" + executable);
      command.addAll(List.of(arguments));
      // The shell records its process ID and then execs the launcher, which execs the JVM, so the
      // recorded process receives the signals sent to it. A detached exec, unlike a background job
      // of a non-interactive shell, does not start with SIGINT ignored.
      var script =
          "echo $$ > "
              + quote(base + ".pid")
              + " && exec "
              + command.stream()
                  .map(InternalNetworkContainer::quote)
                  .collect(Collectors.joining(" "))
              + " < "
              + quote(input)
              + " > "
              + quote(base + ".out")
              + " 2> "
              + quote(base + ".err");
      var docker = DockerClientFactory.instance().client();
      var execId =
          docker.execCreateCmd(runtime.getContainerId()).withCmd("sh", "-c", script).exec().getId();
      docker
          .execStartCmd(execId)
          .withDetach(true)
          .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>())
          .awaitCompletion();
      return new Running() {
        @Override
        public void interrupt() throws Exception {
          shell("kill -INT \"$(cat " + quote(base + ".pid") + ")\"");
        }

        @Override
        public Result await(Duration timeout) throws Exception {
          var deadline = System.nanoTime() + timeout.toNanos();
          InspectExecResponse state;
          // Docker may briefly report an exec as stopped before it records the exit code.
          while (!Boolean.FALSE.equals((state = docker.inspectExecCmd(execId).exec()).isRunning())
              || state.getExitCodeLong() == null) {
            if (System.nanoTime() > deadline) {
              interrupt();
              throw new AssertionError("Timed out: " + command);
            }
            Thread.sleep(100);
          }
          try {
            return new Result(
                Math.toIntExact(Objects.requireNonNull(state.getExitCodeLong())),
                shell("cat " + quote(base + ".out")),
                shell("cat " + quote(base + ".err")));
          } finally {
            shell("rm -f " + quote(base) + ".*");
          }
        }
      };
    }

    private String shell(String script) throws Exception {
      var result = runtime.execInContainer("sh", "-c", script);
      assertThat(result.getExitCode()).as(script + ": " + result.getStderr()).isZero();
      return result.getStdout();
    }

    private static String quote(String value) {
      return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override
    public void close() {
      runtime.stop();
      sources.values().forEach(source -> source.container().stop());
      store.container().stop();
      network.close();
    }
  }
}
