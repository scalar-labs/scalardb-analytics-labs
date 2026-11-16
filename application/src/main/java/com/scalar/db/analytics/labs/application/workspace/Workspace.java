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
import com.scalar.db.analytics.labs.application.construction.ConstructionSqlSettings;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogSettings;
import com.scalar.db.analytics.labs.physical.Secret;
import com.scalar.db.analytics.labs.semantic.StoreSettings;
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
import java.time.Duration;
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
  private static final String CONFIG_SCHEMA_VERSION = "1";
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

  private Workspace(Path directory, Configuration configuration, Secret storePassword) {
    this.directory = directory;
    this.configuration = configuration;
    this.storePassword = storePassword;
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
    return new Workspace(normalized, configuration, Secret.of(password));
  }

  public static Workspace initialize(
      Path directory,
      String catalogName,
      String storeJdbcUrl,
      @Nullable String storeUsername,
      String storePasswordEnv,
      Map<String, String> environment) {
    var configuration =
        new Configuration(
            CONFIG_SCHEMA,
            CONFIG_SCHEMA_VERSION,
            catalogName,
            storeJdbcUrl,
            storeUsername,
            storePasswordEnv,
            null);
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
    var workspace = new Workspace(normalized, configuration, Secret.of(password));
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

  public ApplicationRuntime openRuntime() {
    return ApplicationRuntime.start(catalogSettings());
  }

  /**
   * The settings of the Semantic Catalog's store: the Physical Catalog's store database, in which
   * the Semantic Catalog keeps its own schema.
   */
  public StoreSettings semanticCatalogSettings() {
    return new StoreSettings(
        configuration.storeJdbcUrl(), configuration.storeUsername(), storePassword.reveal());
  }

  /**
   * The workspace's one Semantic Catalog domain, named after its catalog. A catalog name may hold
   * characters a domain identifier cannot, so the name is lowercased and every run of other
   * characters becomes one hyphen.
   */
  public String semanticCatalogDomain() {
    var domain =
        configuration
            .catalogName()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    if (domain.isEmpty()) {
      throw new ApplicationFailure(
          "INVALID_REQUEST", "The catalog name gives no Semantic Catalog domain identifier.");
    }
    return domain.length() > 63 ? domain.substring(0, 63).replaceAll("-+$", "") : domain;
  }

  /**
   * The domain's settings for construction-run SQL. The workspace configuration may set each under
   * {@code semanticCatalog}; an unset one takes its default.
   */
  public ConstructionSqlSettings constructionSqlSettings() {
    var semanticCatalog = configuration.semanticCatalog();
    if (semanticCatalog == null) {
      return ConstructionSqlSettings.defaults();
    }
    return new ConstructionSqlSettings(
        Objects.requireNonNullElse(
            semanticCatalog.sampleSize(), ConstructionSqlSettings.DEFAULT_SAMPLE_SIZE),
        semanticCatalog.constructionQueryTimeLimitSeconds() == null
            ? ConstructionSqlSettings.DEFAULT_TIME_LIMIT
            : Duration.ofSeconds(semanticCatalog.constructionQueryTimeLimitSeconds()));
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
      @Nullable SemanticCatalogConfiguration semanticCatalog) {
    void validate(String errorCode) {
      if (!CONFIG_SCHEMA.equals(schema)
          || !CONFIG_SCHEMA_VERSION.equals(schemaVersion)
          || catalogName == null
          || catalogName.isBlank()
          || storeJdbcUrl == null
          || storeJdbcUrl.isBlank()
          || storePasswordEnv == null
          || storePasswordEnv.isBlank()
          || (storeUsername != null && storeUsername.isBlank())
          || (storeJdbcUrl != null && containsJdbcCredentials(storeJdbcUrl))
          || (semanticCatalog != null && !semanticCatalog.isValid())) {
        throw new ApplicationFailure(
            errorCode, "The workspace configuration is invalid or unsafe.");
      }
    }
  }

  /**
   * The Semantic Catalog settings of the workspace's domain.
   *
   * @param sampleSize the most rows read of a sampled table in construction-run SQL
   * @param constructionQueryTimeLimitSeconds how long one construction query may take
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SemanticCatalogConfiguration(
      @Nullable Long sampleSize, @Nullable Long constructionQueryTimeLimitSeconds) {

    boolean isValid() {
      return (sampleSize == null
              || (sampleSize > 0 && sampleSize <= ConstructionSqlSettings.MAX_SAMPLE_SIZE))
          && (constructionQueryTimeLimitSeconds == null
              || (constructionQueryTimeLimitSeconds > 0
                  && constructionQueryTimeLimitSeconds
                      <= ConstructionSqlSettings.MAX_TIME_LIMIT.toSeconds()));
    }
  }
}
