/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.cli.CliOutput;
import com.scalar.db.analytics.labs.application.construction.BuildTaskRun;
import com.scalar.db.analytics.labs.application.workspace.Workspace;
import com.scalar.db.analytics.labs.physical.PhysicalCatalogException;
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "semantic", description = "Constructs the Semantic Catalog.")
public final class SemanticCommand implements Runnable {
  private SemanticCommand() {}

  /**
   * Creates the command with its {@code build} subcommand, which the caller keeps to deliver
   * interrupts to.
   */
  public static CommandLine create(SemanticBuildCommand build) {
    var command = new CommandLine(new SemanticCommand());
    command.addSubcommand("build", build);
    return command;
  }

  @Override
  public void run() {
    throw new CommandLine.ParameterException(
        new CommandLine(this), "A semantic command is required.");
  }

  /** Runs the workspace domain's build tasks, and with {@code --watch} keeps running. */
  @Command(
      name = "build",
      description =
          "Runs the Semantic Catalog build tasks, printing one line per finished task; with"
              + " --watch, keeps running until interrupted.")
  public static final class SemanticBuildCommand implements Callable<Integer> {
    private final Map<String, String> environment;
    private final AtomicBoolean watching = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    @Option(names = "--workspace", required = true, paramLabel = "DIR")
    private @Nullable Path workspaceDirectory;

    @Option(
        names = "--watch",
        description = "Keep running, picking up new build tasks, until interrupted.")
    private boolean watch;

    @Spec private @Nullable CommandSpec commandSpec;

    public SemanticBuildCommand(Map<String, String> environment) {
      this.environment = environment;
    }

    /**
     * Asks a watching run to stop after its current task. Returns false when no watching run is
     * active or a stop was already asked for, so the caller can stop the process at once.
     */
    public boolean requestStop() {
      return watching.get() && stopRequested.compareAndSet(false, true);
    }

    @Override
    public Integer call() throws JsonProcessingException {
      var out = Objects.requireNonNull(commandSpec, "commandSpec").commandLine().getOut();
      try {
        var workspace =
            Workspace.load(
                Objects.requireNonNull(workspaceDirectory, "workspaceDirectory"), environment);
        var mode =
            watch ? new BuildTaskRun.Mode.Watch(stopRequested::get) : new BuildTaskRun.Mode.Drain();
        stopRequested.set(false);
        watching.set(watch);
        try (var runtime = workspace.openRuntime()) {
          var summary =
              BuildTaskRun.run(
                  workspace,
                  runtime,
                  mode,
                  event -> {
                    try {
                      CliOutput.write(
                          out,
                          new TaskEventOutput(
                              "scalardb-analytics/build-task-event",
                              "1",
                              event.task(),
                              event.awaitingReview()));
                    } catch (JsonProcessingException e) {
                      throw new UncheckedIOException(e);
                    }
                  });
          var failed =
              summary.finished().stream()
                  .filter(task -> task.status() instanceof BuildTask.Status.Failed)
                  .count();
          CliOutput.write(
              out,
              new RunOutput(
                  "scalardb-analytics/build-run",
                  "1",
                  summary.reset(),
                  summary.finished().size() - failed,
                  failed));
          return CliOutput.SUCCESS;
        } catch (PhysicalCatalogException e) {
          throw ApplicationFailure.from(e);
        } finally {
          watching.set(false);
        }
      } catch (ApplicationFailure e) {
        return CliOutput.writeFailure(out, e);
      }
    }
  }

  private record TaskEventOutput(
      String schema, String schemaVersion, BuildTask task, int awaitingReview) {}

  private record RunOutput(
      String schema, String schemaVersion, List<Long> reset, long succeeded, long failed) {}
}
