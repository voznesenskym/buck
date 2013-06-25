/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.command.Build;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildEvents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
public class BuildCommand extends AbstractCommandRunner<BuildCommandOptions> {

  private Build build;

  private ImmutableList<BuildTarget> buildTargets = ImmutableList.of();

  public BuildCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  BuildCommandOptions createOptions(BuckConfig buckConfig) {
    return new BuildCommandOptions(buckConfig);
  }

  @Override
  public synchronized int runCommandWithOptions(BuildCommandOptions options)
      throws IOException {
    // Set the logger level based on the verbosity option.
    Verbosity verbosity = console.getVerbosity();
    Logging.setLoggingLevelForVerbosity(verbosity);

    try {
      buildTargets = getBuildTargets(options.getArgumentsFormattedAsBuildTargets());
    } catch (NoSuchBuildTargetException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    if (buildTargets.isEmpty()) {
      console.printBuildFailure("Must specify at least one build target.");
      return 1;
    }

    // Parse the build files to create a DependencyGraph.
    DependencyGraph dependencyGraph;
    try {
      dependencyGraph = getParser().parseBuildFilesForTargets(buildTargets,
          options.getDefaultIncludes(),
          getEventBus());
    } catch (NoSuchBuildTargetException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    // Create and execute the build.
    build = options.createBuild(
        options.getBuckConfig(),
        dependencyGraph,
        getProjectFilesystem(),
        getArtifactCache(),
        console,
        getEventBus());
    getStdErr().printf("BUILDING %s\n", Joiner.on(' ').join(buildTargets));
    int exitCode = executeBuildAndPrintAnyFailuresToConsole(build, console);

    if (exitCode != 0) {
      return exitCode;
    }

    console.getAnsi().printlnHighlightedSuccessText(getStdErr(), "BUILD SUCCESSFUL");
    return 0;
  }

  static int executeBuildAndPrintAnyFailuresToConsole(Build build, Console console) {
    Set<BuildRule> rulesToBuild = build.getDependencyGraph().getNodesWithNoIncomingEdges();
    build.getExecutionContext().getEventBus().post(BuildEvents.buildStarted(rulesToBuild));
    int exitCode;
    try {
      // Get the Future representing the build and then block until everything is built.
      build.executeBuild(rulesToBuild).get();
      exitCode = 0;
    } catch (IOException e) {
      console.printBuildFailureWithoutStacktrace(e);
      exitCode = 1;
    } catch (StepFailedException e) {
      console.printBuildFailureWithoutStacktrace(e);
      exitCode = e.getExitCode();
    } catch (ExecutionException e) {
      // This is likely a checked exception that was caught while building a build rule.
      Throwable cause = e.getCause();
      if (cause instanceof HumanReadableException) {
        throw ((HumanReadableException)cause);
      } else if (cause instanceof ExceptionWithHumanReadableMessage) {
        throw new HumanReadableException((ExceptionWithHumanReadableMessage)cause);
      } else {
        if (cause instanceof RuntimeException) {
          console.printBuildFailureWithStacktrace(e);
        } else {
          console.printBuildFailureWithoutStacktrace(e);
        }
        exitCode = 1;
      }
    } catch (InterruptedException e) {
      // This suggests an error in Buck rather than a user error.
      console.printBuildFailureWithoutStacktrace(e);
      exitCode = 1;
    }

    build.getExecutionContext().getEventBus().post(BuildEvents.buildFinished(exitCode));
    return exitCode;
  }

  Build getBuild() {
    Preconditions.checkNotNull(build);
    return build;
  }

  ImmutableList<BuildTarget> getBuildTargets() {
    return ImmutableList.copyOf(buildTargets);
  }

  @Override
  String getUsageIntro() {
    return "Specify one build rule to build.";
  }

}
