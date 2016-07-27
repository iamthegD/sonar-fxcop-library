/*
 * SonarQube FxCop Library
 * Copyright (C) 2014-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.fxcop;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.apache.commons.lang.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputFile.Type;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.issue.Issuable;
import org.sonar.api.issue.Issuable.IssueBuilder;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.ActiveRule;

public class FxCopSensor implements Sensor {

  private static final String CUSTOM_RULE_KEY = "CustomRuleTemplate";
  private static final String CUSTOM_RULE_CHECK_ID_PARAMETER = "CheckId";
  private static final Logger LOG = LoggerFactory.getLogger(FxCopSensor.class);

  private final FxCopConfiguration fxCopConf;
  private final Settings settings;
  private final RulesProfile profile;
  private final FileSystem fs;
  private final ResourcePerspectives perspectives;

  public FxCopSensor(FxCopConfiguration fxCopConf, Settings settings, RulesProfile profile, FileSystem fs, ResourcePerspectives perspectives) {
    this.fxCopConf = fxCopConf;
    this.settings = settings;
    this.profile = profile;
    this.fs = fs;
    this.perspectives = perspectives;
  }

  @Override
  public boolean shouldExecuteOnProject(Project project) {
    boolean shouldExecute;

    if (!hasFilesToAnalyze() || !SystemUtils.IS_OS_WINDOWS) {
      shouldExecute = false;
    } else if (profile.getActiveRulesByRepository(fxCopConf.repositoryKey()).isEmpty()) {
      LOG.info("All FxCop rules are disabled, skipping its execution.");
      shouldExecute = false;
    } else {
      shouldExecute = true;
    }

    return shouldExecute;
  }

  private boolean hasFilesToAnalyze() {
    return fs.files(fs.predicates().and(fs.predicates().hasLanguage(fxCopConf.languageKey()), fs.predicates().hasType(Type.MAIN))).iterator().hasNext();
  }

  @Override
  public void analyse(Project project, SensorContext context) {
    analyse(new FxCopRulesetWriter(), new FxCopReportParser(), new FxCopExecutor(), perspectives.as(Issuable.class, (Resource) project));
  }

  @VisibleForTesting
  void analyse(FxCopRulesetWriter writer, FxCopReportParser parser, FxCopExecutor executor, Issuable projectIssuable) {
    fxCopConf.checkProperties(settings);

    File reportFile;
    String reportPath = settings.getString(fxCopConf.reportPathPropertyKey());
    if (reportPath == null) {
      File rulesetFile = new File(fs.workDir(), "fxcop-sonarqube.ruleset");
      writer.write(enabledRuleConfigKeys(), rulesetFile);

      reportFile = new File(fs.workDir(), "fxcop-report.xml");

      executor.execute(settings.getString(fxCopConf.fxCopCmdPropertyKey()), settings.getString(fxCopConf.assemblyPropertyKey()),
        rulesetFile, reportFile, settings.getInt(fxCopConf.timeoutPropertyKey()), settings.getBoolean(fxCopConf.aspnetPropertyKey()),
        splitOnCommas(settings.getString(fxCopConf.directoriesPropertyKey())), splitOnCommas(settings.getString(fxCopConf.referencesPropertyKey())));
    } else {
      LOG.debug("Using the provided FxCop report" + reportPath);
      reportFile = new File(reportPath);
    }

    for (FxCopIssue issue : parser.parse(reportFile)) {
      String absolutePath = getSourceFileAbsolutePath(issue);

      InputFile inputFile = null;
      if (absolutePath != null) {
        inputFile = fs.inputFile(fs.predicates().hasAbsolutePath(absolutePath));
      }

      String messageLocation = "";
      Issuable issuable;
      boolean isOnProjectIssuable = false;
      if (inputFile != null) {
        issuable = perspectives.as(Issuable.class, inputFile);
      } else {
        issuable = projectIssuable;
        isOnProjectIssuable = true;
        messageLocation = createMessageLocation(absolutePath, issue.line());
      }

      if (issuable == null) {
        LOG.warn("Skipping the FxCop issue at line " + issue.reportLine() + " which has no associated SonarQube issuable.");
        continue;
      }

      IssueBuilder issueBuilder = issuable.newIssueBuilder()
        .ruleKey(RuleKey.of(fxCopConf.repositoryKey(), ruleKey(issue.ruleConfigKey())))
        .message(messageLocation + issue.message());

      Integer line = fxCopToSonarQubeLine(issue.line(), isOnProjectIssuable);
      if (line != null) {
        issueBuilder.line(line);
      }

      issuable.addIssue(issueBuilder.build());
    }
  }

  private static String createMessageLocation(String absolutePath, Integer line) {
    String messageLocation = "";
    if (absolutePath != null) {
      messageLocation += absolutePath;

      if (line != null) {
        messageLocation += " line " + line;
      }

      messageLocation += ": ";
    }
    return messageLocation;
  }

  @CheckForNull
  private static Integer fxCopToSonarQubeLine(@Nullable Integer fxcopLine, boolean isOnProjectIssuable) {
    if (fxcopLine == null || isOnProjectIssuable) {
      return null;
    }

    return fxcopLine <= 0 ? null : fxcopLine;
  }

  private static List<String> splitOnCommas(@Nullable String property) {
    if (property == null) {
      return ImmutableList.of();
    } else {
      return ImmutableList.copyOf(Splitter.on(",").trimResults().omitEmptyStrings().split(property));
    }
  }

  @CheckForNull
  private static String getSourceFileAbsolutePath(FxCopIssue issue) {
    if (issue.path() == null || issue.file() == null) {
      return null;
    }

    File file = new File(new File(issue.path()), issue.file());
    return file.getAbsolutePath();
  }

  private List<String> enabledRuleConfigKeys() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (ActiveRule activeRule : profile.getActiveRulesByRepository(fxCopConf.repositoryKey())) {
      if (!CUSTOM_RULE_KEY.equals(activeRule.getRuleKey())) {
        String effectiveConfigKey = activeRule.getConfigKey();
        if (effectiveConfigKey == null) {
          effectiveConfigKey = activeRule.getParameter(CUSTOM_RULE_CHECK_ID_PARAMETER);
        }

        builder.add(effectiveConfigKey);
      }
    }
    return builder.build();
  }

  private String ruleKey(String ruleConfigKey) {
    for (ActiveRule activeRule : profile.getActiveRulesByRepository(fxCopConf.repositoryKey())) {
      if (ruleConfigKey.equals(activeRule.getConfigKey()) || ruleConfigKey.equals(activeRule.getParameter(CUSTOM_RULE_CHECK_ID_PARAMETER))) {
        return activeRule.getRuleKey();
      }
    }

    throw new IllegalStateException(
      "Unable to find the rule key corresponding to the rule config key \"" + ruleConfigKey + "\" in repository \"" + fxCopConf.repositoryKey() + "\".");
  }

}
