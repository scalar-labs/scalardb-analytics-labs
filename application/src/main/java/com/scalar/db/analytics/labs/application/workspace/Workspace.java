/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.ApplicationRuntime;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogSettings;
import com.scalar.db.analytics.labs.physical.Secret;
import com.scalar.db.analytics.labs.semantic.StorageSettings;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The persistent configuration that binds one directory to one Physical Catalog catalog. */
public final class Workspace {
  public static final String CONFIG_FILE_NAME = "workspace.json";
  private static final String CONFIG_SCHEMA = "scalardb-analytics/workspace-configuration";
  private static final String CONFIG_SCHEMA_VERSION = "2";
  private static final Set<String> READABLE_SCHEMA_VERSIONS = Set.of("1", "2");

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");
  private static final Set<PosixFilePermission> UNSAFE_WRITE_PERMISSIONS =
      Set.of(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
  private static final Pattern URL_CREDENTIAL_PROPERTY =
      Pattern.compile("(?:[?;&]|\\b)(?:user|username|password|passwd|pwd|sslpassword)=");
  private static final Pattern URL_AUTHORITY_CREDENTIAL = Pattern.compile("jdbc:[^:]+://[^/?#@]*@");
  private static final Pattern ORACLE_THIN_CREDENTIAL =
      Pattern.compile("jdbc:oracle:thin:[^:@/]+/[^@]+@");

  private final Path directory;
  private final Configuration configuration;
  private final Secret storePassword;
  private final Map<String, String> environment;

  private Workspace(
      Path directory,
      Configuration configuration,
      Secret storePassword,
      Map<String, String> environment) {
    this.directory = directory;
    this.configuration = configuration;
    this.storePassword = storePassword;
    this.environment = environment;
  }

  public static Workspace load(Path directory, Map<String, String> environment) {
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(environment, "environment");
    var normalized = directory.toAbsolutePath().normalize();
    var path = normalized.resolve(CONFIG_FILE_NAME);
    if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(normalized)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || hasUnsafeWritePermissions(normalized)
        || hasUnsafeWritePermissions(path)) {
      throw new ApplicationFailure(
          "INVALID_WORKSPACE", "The workspace does not exist or is invalid.");
    }
    final Configuration configuration;
    try {
      configuration = OBJECT_MAPPER.readValue(path.toFile(), Configuration.class);
    } catch (IOException | RuntimeException e) {
      throw new ApplicationFailure("INVALID_WORKSPACE", "The workspace configuration is invalid.");
    }
    configuration.validate("INVALID_WORKSPACE");
    var password = environment.get(configuration.storePasswordEnv());
    if (password == null) {
      throw new ApplicationFailure(
          "MISSING_CREDENTIAL",
          "The Physical Catalog store password environment variable is not set.");
    }
    return new Workspace(normalized, configuration, Secret.of(password), environment);
  }

  public static Workspace initialize(
      Path directory,
      String catalogName,
      String storeJdbcUrl,
      @Nullable String storeUsername,
      String storePasswordEnv,
      List<String> semanticsOwners,
      Map<String, String> environment) {
    var configuration =
        new Configuration(
            CONFIG_SCHEMA,
            CONFIG_SCHEMA_VERSION,
            catalogName,
            storeJdbcUrl,
            storeUsername,
            storePasswordEnv,
            List.copyOf(semanticsOwners));
    configuration.validate("INVALID_REQUEST");
    var password = environment.get(storePasswordEnv);
    if (password == null) {
      throw new ApplicationFailure(
          "MISSING_CREDENTIAL",
          "The Physical Catalog store password environment variable is not set.");
    }
    var normalized = directory.toAbsolutePath().normalize();
    var path = normalized.resolve(CONFIG_FILE_NAME);
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new ApplicationFailure(
          "WORKSPACE_ALREADY_EXISTS", "The workspace is already initialized.");
    }
    var workspace = new Workspace(normalized, configuration, Secret.of(password), environment);
    var configurationWritten = false;
    var configurationFileCreated = false;
    try {
      createPrivateDirectory(normalized);
      var json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);
      createPrivateFile(path);
      configurationFileCreated = true;
      Files.writeString(
          path,
          json + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      configurationWritten = true;
    } catch (JsonProcessingException e) {
      throw new ApplicationFailure(
          "WORKSPACE_WRITE_FAILED", "Could not encode the workspace configuration.");
    } catch (FileAlreadyExistsException e) {
      throw new ApplicationFailure(
          "WORKSPACE_ALREADY_EXISTS", "The workspace is already initialized.");
    } catch (IOException e) {
      if (configurationFileCreated) {
        deleteConfigurationQuietly(path);
      }
      throw new ApplicationFailure(
          "WORKSPACE_WRITE_FAILED", "Could not write the workspace configuration.");
    }
    var catalogCreated = false;
    try (var runtime = workspace.openRuntime()) {
      runtime.physicalCatalog().createCatalog(catalogName);
      catalogCreated = true;
    } catch (com.scalar.db.analytics.labs.physical.PhysicalCatalogException e) {
      if (configurationWritten && !catalogCreated) {
        deleteConfigurationQuietly(path);
      }
      throw ApplicationFailure.from(e);
    } catch (RuntimeException e) {
      if (configurationWritten && !catalogCreated) {
        deleteConfigurationQuietly(path);
      }
      throw e;
    }
    try {
      SemanticCatalogs.create(workspace, semanticsOwners.getFirst());
    } catch (RuntimeException e) {
      deleteConfigurationQuietly(path);
      throw e;
    }
    return workspace;
  }

  private static void createPrivateDirectory(Path directory) throws IOException {
    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(directory)
          || hasUnsafeWritePermissions(directory)) {
        throw new IOException("The workspace directory is not private.");
      }
      return;
    }
    Files.createDirectories(directory);
    if (supportsPosixPermissions(directory)) {
      Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
    }
  }

  private static void createPrivateFile(Path path) throws IOException {
    if (supportsPosixPermissions(Objects.requireNonNull(path.getParent(), "workspace directory"))) {
      Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
    } else {
      Files.createFile(path);
    }
  }

  private static boolean supportsPosixPermissions(Path path) throws IOException {
    return Files.getFileStore(path).supportsFileAttributeView("posix");
  }

  private static boolean hasUnsafeWritePermissions(Path path) {
    try {
      if (!supportsPosixPermissions(path)) {
        return false;
      }
      return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream()
          .anyMatch(UNSAFE_WRITE_PERMISSIONS::contains);
    } catch (IOException | UnsupportedOperationException e) {
      return true;
    }
  }

  private static void deleteConfigurationQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // The primary failure remains more useful than a best-effort rollback failure.
    }
  }

  private static boolean containsJdbcCredentials(String jdbcUrl) {
    var decoded = jdbcUrl;
    for (var pass = 0; pass < 2; pass++) {
      try {
        decoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        return true;
      }
    }
    var normalized = decoded.toLowerCase(Locale.ROOT);
    return URL_CREDENTIAL_PROPERTY.matcher(normalized).find()
        || URL_AUTHORITY_CREDENTIAL.matcher(normalized).find()
        || ORACLE_THIN_CREDENTIAL.matcher(normalized).find();
  }

  public Path directory() {
    return directory;
  }

  public String catalogName() {
    return configuration.catalogName();
  }

  /** The principals that may decide on Semantic Catalog candidates, or none for a v1 workspace. */
  public List<String> semanticsOwners() {
    var owners = configuration.semanticsOwners();
    return owners == null ? List.of() : owners;
  }

  /**
   * The store of the workspace's Semantic Catalog domain: the Physical Catalog's store database,
   * with the same connection settings.
   */
  public StorageSettings semanticStorage() {
    return new StorageSettings(
        configuration.storeJdbcUrl(), configuration.storeUsername(), storePassword.reveal());
  }

  /** The process environment, which carries credentials and the language model selection. */
  public Map<String, String> environment() {
    return environment;
  }

  public ApplicationRuntime openRuntime() {
    return ApplicationRuntime.start(catalogSettings());
  }

  private PhysicalCatalogSettings catalogSettings() {
    return new PhysicalCatalogSettings(
        configuration.storeJdbcUrl(),
        Optional.ofNullable(configuration.storeUsername()),
        storePassword);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Configuration(
      String schema,
      String schemaVersion,
      String catalogName,
      String storeJdbcUrl,
      @Nullable String storeUsername,
      String storePasswordEnv,
      @Nullable List<String> semanticsOwners) {
    void validate(String errorCode) {
      if (!CONFIG_SCHEMA.equals(schema)
          || !READABLE_SCHEMA_VERSIONS.contains(schemaVersion)
          || (CONFIG_SCHEMA_VERSION.equals(schemaVersion)
              && (semanticsOwners == null
                  || semanticsOwners.isEmpty()
                  || semanticsOwners.stream().anyMatch(String::isBlank)))
          || catalogName == null
          || catalogName.isBlank()
          || storeJdbcUrl == null
          || storeJdbcUrl.isBlank()
          || storePasswordEnv == null
          || storePasswordEnv.isBlank()
          || (storeUsername != null && storeUsername.isBlank())
          || (storeJdbcUrl != null && containsJdbcCredentials(storeJdbcUrl))) {
        throw new ApplicationFailure(
            errorCode, "The workspace configuration is invalid or unsafe.");
      }
    }
  }
}
