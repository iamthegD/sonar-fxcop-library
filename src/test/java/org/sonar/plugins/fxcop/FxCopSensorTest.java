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

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.List;
import org.apache.commons.lang.SystemUtils;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultFileSystem;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.issue.Issuable;
import org.sonar.api.issue.Issuable.IssueBuilder;
import org.sonar.api.issue.Issue;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.ActiveRule;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class FxCopSensorTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void shouldExecuteOnProject() {
    Assume.assumeTrue(SystemUtils.IS_OS_WINDOWS);

    Settings settings = mock(Settings.class);
    RulesProfile profile = mock(RulesProfile.class);
    DefaultFileSystem fs = new DefaultFileSystem();
    ResourcePerspectives perspectives = mock(ResourcePerspectives.class);

    Project project = mock(Project.class);

    FxCopSensor sensor = new FxCopSensor(
      new FxCopConfiguration("foo", "foo-fxcop", "", "", "", "", "", "", ""),
      settings, profile, fs, perspectives);

    assertThat(sensor.shouldExecuteOnProject(project)).isFalse();

    fs.add(new DefaultInputFile("bar").setAbsolutePath("bar").setLanguage("bar"));
    assertThat(sensor.shouldExecuteOnProject(project)).isFalse();

    fs.add(new DefaultInputFile("foo").setAbsolutePath("foo").setLanguage("foo"));
    assertThat(sensor.shouldExecuteOnProject(project)).isFalse();

    when(profile.getActiveRulesByRepository("foo-fxcop")).thenReturn(ImmutableList.<ActiveRule>of());
    assertThat(sensor.shouldExecuteOnProject(project)).isFalse();

    when(profile.getActiveRulesByRepository("foo-fxcop")).thenReturn(ImmutableList.of(mock(ActiveRule.class)));
    assertThat(sensor.shouldExecuteOnProject(project)).isTrue();
  }

  @Test
  public void analyze() throws Exception {
    Settings settings = mock(Settings.class);
    RulesProfile profile = mock(RulesProfile.class);
    DefaultFileSystem fs = new DefaultFileSystem();
    ResourcePerspectives perspectives = mock(ResourcePerspectives.class);

    FxCopConfiguration fxCopConf = mock(FxCopConfiguration.class);
    when(fxCopConf.languageKey()).thenReturn("foo");
    when(fxCopConf.repositoryKey()).thenReturn("foo-fxcop");
    when(fxCopConf.assemblyPropertyKey()).thenReturn("assemblyKey");
    when(fxCopConf.fxCopCmdPropertyKey()).thenReturn("fxcopcmdPath");
    when(fxCopConf.timeoutPropertyKey()).thenReturn("timeout");
    when(fxCopConf.aspnetPropertyKey()).thenReturn("aspnet");
    when(fxCopConf.directoriesPropertyKey()).thenReturn("directories");
    when(fxCopConf.referencesPropertyKey()).thenReturn("references");

    FxCopSensor sensor = new FxCopSensor(
      fxCopConf,
      settings, profile, fs, perspectives);
    when(settings.hasKey("assemblyKey")).thenReturn(true);
    when(settings.hasKey("fxcopcmdPath")).thenReturn(true);

    List<ActiveRule> activeRules = mockActiveRules("CA0000", "CA1000", "CustomRuleTemplate", "CR1000");
    when(profile.getActiveRulesByRepository("foo-fxcop")).thenReturn(activeRules);

    FxCopExecutor executor = mock(FxCopExecutor.class);

    File workingDir = new File(new File("target/FxCopSensorTest/working-dir").getAbsolutePath());
    fs.setWorkDir(workingDir);

    when(settings.getString("assemblyKey")).thenReturn("MyLibrary.dll");
    when(settings.getString("fxcopcmdPath")).thenReturn("FxCopCmd.exe");
    when(settings.getInt("timeout")).thenReturn(42);
    when(settings.getBoolean("aspnet")).thenReturn(true);
    when(settings.getString("directories")).thenReturn(" c:/,,  d:/ ");
    when(settings.getString("references")).thenReturn(null);

    InputFile class3InputFile = new DefaultInputFile("Class3.cs").setAbsolutePath(new File(new File("basePath"), "Class3.cs").getAbsolutePath()).setLanguage("foo");
    // Class4 missing on purpose
    InputFile class5InputFile = new DefaultInputFile("Class5.cs").setAbsolutePath(new File(new File("basePath"), "Class5.cs").getAbsolutePath()).setLanguage("foo");
    InputFile class6InputFile = new DefaultInputFile("Class6.cs").setAbsolutePath(new File(new File("basePath"), "Class6.cs").getAbsolutePath()).setLanguage("foo");
    InputFile class7InputFile = new DefaultInputFile("Class7.cs").setAbsolutePath(new File(new File("basePath"), "Class7.cs").getAbsolutePath()).setLanguage("foo");
    // Class8 has language "bar"
    InputFile class8InputFile = new DefaultInputFile("Class8.cs").setAbsolutePath(new File(new File("basePath"), "Class8.cs").getAbsolutePath()).setLanguage("bar");
    InputFile class9InputFile = new DefaultInputFile("Class9.cs").setAbsolutePath(new File(new File("basePath"), "Class9.cs").getAbsolutePath()).setLanguage("foo");

    fs.add(class3InputFile);
    // Class4 missing on purpose
    fs.add(class5InputFile);
    fs.add(class6InputFile);
    fs.add(class7InputFile);
    fs.add(class8InputFile);
    fs.add(class9InputFile);

    Issue issue1 = mock(Issue.class);
    IssueBuilder issueBuilder1 = mockIssueBuilder();
    when(issueBuilder1.build()).thenReturn(issue1);

    Issue issue2 = mock(Issue.class);
    IssueBuilder issueBuilder2 = mockIssueBuilder();
    when(issueBuilder2.build()).thenReturn(issue2);

    Issue issue3 = mock(Issue.class);
    IssueBuilder issueBuilder3 = mockIssueBuilder();
    when(issueBuilder3.build()).thenReturn(issue3);

    Issue issue4 = mock(Issue.class);
    IssueBuilder issueBuilder4 = mockIssueBuilder();
    when(issueBuilder4.build()).thenReturn(issue4);

    Issue issue5 = mock(Issue.class);
    IssueBuilder issueBuilder5 = mockIssueBuilder();
    when(issueBuilder5.build()).thenReturn(issue5);

    Issuable issuable = mock(Issuable.class);
    when(perspectives.as(Mockito.eq(Issuable.class), Mockito.any(InputFile.class))).thenReturn(issuable);
    when(perspectives.as(Issuable.class, class7InputFile)).thenReturn(null);
    when(issuable.newIssueBuilder()).thenReturn(issueBuilder1, issueBuilder2, issueBuilder3, issueBuilder4, issueBuilder5);

    Issue projectIssue1 = mock(Issue.class);
    IssueBuilder projectIssueBuilder1 = mockIssueBuilder();
    when(projectIssueBuilder1.build()).thenReturn(projectIssue1);

    Issue projectIssue2 = mock(Issue.class);
    IssueBuilder projectIssueBuilder2 = mockIssueBuilder();
    when(projectIssueBuilder2.build()).thenReturn(projectIssue2);

    Issue projectIssue3 = mock(Issue.class);
    IssueBuilder projectIssueBuilder3 = mockIssueBuilder();
    when(projectIssueBuilder3.build()).thenReturn(projectIssue3);

    Issuable projectIssuable = mock(Issuable.class);
    when(projectIssuable.newIssueBuilder()).thenReturn(projectIssueBuilder1, projectIssueBuilder2, projectIssueBuilder3);

    FxCopRulesetWriter writer = mock(FxCopRulesetWriter.class);

    FxCopReportParser parser = mock(FxCopReportParser.class);
    when(parser.parse(new File(workingDir, "fxcop-report.xml"))).thenReturn(
      ImmutableList.of(
        new FxCopIssue(100, "CA0000", null, "Class1.cs", 1, "Dummy message"), // no path -> project
        new FxCopIssue(200, "CA0000", "basePath", null, 2, "Dummy message"), // no filename -> project
        new FxCopIssue(300, "CA0000", "basePath", "Class3.cs", null, "Dummy message"), // no line -> on file
        new FxCopIssue(400, "CA0000", "basePath", "Class4.cs", 4, "First message"), // no input file -> on project
        new FxCopIssue(500, "CA0000", "basePath", "Class5.cs", 0, "Second message"), // all good but line 0 -> on file
        new FxCopIssue(600, "CA1000", "basePath", "Class6.cs", 6, "Third message"), // all good -> on file+line
        new FxCopIssue(700, "CA0000", "basePath", "Class7.cs", 7, "Fourth message"), // null issuable but has input file -> skipped
        new FxCopIssue(800, "CA0000", "basePath", "Class8.cs", 8, "Fifth message"), // language "bar" -> on file+line
        new FxCopIssue(800, "CR1000", "basePath", "Class9.cs", 9, "Sixth message"))); // all good -> on file+line

    sensor.analyse(writer, parser, executor, projectIssuable);

    verify(writer).write(ImmutableList.of("CA0000", "CA1000", "CR1000"), new File(workingDir, "fxcop-sonarqube.ruleset"));
    verify(executor).execute("FxCopCmd.exe", "MyLibrary.dll", new File(workingDir, "fxcop-sonarqube.ruleset"), new File(workingDir, "fxcop-report.xml"), 42, true,
      ImmutableList.of("c:/", "d:/"), ImmutableList.<String>of());

    verify(issuable).addIssue(issue1);
    verify(issuable).addIssue(issue2);
    verify(issuable).addIssue(issue3);
    verify(issuable).addIssue(issue4);
    verify(issuable).addIssue(issue5);

    verify(issueBuilder1).ruleKey(RuleKey.of("foo-fxcop", "_CA0000"));
    verify(issueBuilder1, Mockito.never()).line(Mockito.anyInt());
    verify(issueBuilder1).message("Dummy message");

    verify(issueBuilder2).ruleKey(RuleKey.of("foo-fxcop", "_CA0000"));
    verify(issueBuilder2, Mockito.never()).line(Mockito.anyInt());
    verify(issueBuilder2).message("Second message");

    verify(issueBuilder3).ruleKey(RuleKey.of("foo-fxcop", "_CA1000"));
    verify(issueBuilder3).line(6);
    verify(issueBuilder3).message("Third message");

    verify(issueBuilder4).ruleKey(RuleKey.of("foo-fxcop", "_CA0000"));
    verify(issueBuilder4).line(8);
    verify(issueBuilder4).message("Fifth message");

    verify(issueBuilder5).ruleKey(RuleKey.of("foo-fxcop", "CustomRuleTemplate_42"));
    verify(issueBuilder5).line(9);
    verify(issueBuilder5).message("Sixth message");

    verify(projectIssuable).addIssue(projectIssue1);
    verify(projectIssuable).addIssue(projectIssue2);
    verify(projectIssuable).addIssue(projectIssue3);

    verify(projectIssueBuilder1).ruleKey(RuleKey.of("foo-fxcop", "_CA0000"));
    verify(projectIssueBuilder1, Mockito.never()).line(Mockito.anyInt());
    verify(projectIssueBuilder1).message("Dummy message");

    verify(projectIssueBuilder2).ruleKey(RuleKey.of("foo-fxcop", "_CA0000"));
    verify(projectIssueBuilder2, Mockito.never()).line(Mockito.anyInt());
    verify(projectIssueBuilder2).message("Dummy message");

    verify(projectIssueBuilder3).ruleKey(RuleKey.of("foo-fxcop", "_CA0000"));
    verify(projectIssueBuilder3, Mockito.never()).line(Mockito.anyInt());
    verify(projectIssueBuilder3).message(new File(new File("basePath"), "Class4.cs").getAbsolutePath() + " line 4: First message");
  }

  @Test
  public void analyze_with_report() {
    Settings settings = new Settings();
    RulesProfile profile = mock(RulesProfile.class);
    FileSystem fs = mock(FileSystem.class);
    ResourcePerspectives perspectives = mock(ResourcePerspectives.class);

    FxCopConfiguration fxCopConf = mock(FxCopConfiguration.class);
    when(fxCopConf.repositoryKey()).thenReturn("foo-fxcop");
    when(fxCopConf.reportPathPropertyKey()).thenReturn("reportPath");

    FxCopSensor sensor = new FxCopSensor(
      fxCopConf,
      settings, profile, fs, perspectives);

    File reportFile = new File("src/test/resources/FxCopSensorTest/fxcop-report.xml");
    settings.setProperty("reportPath", reportFile.getAbsolutePath());

    FxCopRulesetWriter writer = mock(FxCopRulesetWriter.class);
    FxCopReportParser parser = mock(FxCopReportParser.class);
    FxCopExecutor executor = mock(FxCopExecutor.class);

    sensor.analyse(writer, parser, executor, mock(Issuable.class));

    verify(writer, never()).write(anyListOf(String.class), any(File.class));
    verify(executor, never()).execute(
      anyString(), anyString(), any(File.class), any(File.class), anyInt(), anyBoolean(), anyListOf(String.class), anyListOf(String.class));

    verify(parser).parse(new File(reportFile.getAbsolutePath()));
  }

  @Test
  public void check_properties() {
    thrown.expectMessage("No FxCop analysis has been performed on this project");

    FxCopConfiguration fxCopConf = new FxCopConfiguration("", "", "fooAssemblyKey", "", "", "", "", "", "");
    new FxCopSensor(fxCopConf, mock(Settings.class), mock(RulesProfile.class), mock(FileSystem.class), mock(ResourcePerspectives.class))
      .analyse(mock(Project.class), mock(SensorContext.class));
  }

  private static IssueBuilder mockIssueBuilder() {
    IssueBuilder issueBuilder = mock(IssueBuilder.class);
    when(issueBuilder.ruleKey(Mockito.any(RuleKey.class))).thenReturn(issueBuilder);
    when(issueBuilder.line(Mockito.anyInt())).thenReturn(issueBuilder);
    when(issueBuilder.message(Mockito.anyString())).thenReturn(issueBuilder);
    return issueBuilder;
  }

  private static List<ActiveRule> mockActiveRules(String... activeConfigRuleKeys) {
    ImmutableList.Builder<ActiveRule> builder = ImmutableList.builder();
    for (String activeConfigRuleKey : activeConfigRuleKeys) {
      ActiveRule activeRule = mock(ActiveRule.class);
      if ("CustomRuleTemplate".equals(activeConfigRuleKey)) {
        when(activeRule.getRuleKey()).thenReturn(activeConfigRuleKey);
      } else if (activeConfigRuleKey.startsWith("CR")) {
        when(activeRule.getRuleKey()).thenReturn("CustomRuleTemplate_42");
        when(activeRule.getParameter("CheckId")).thenReturn(activeConfigRuleKey);
      } else if (activeConfigRuleKey.startsWith("CA")) {
        when(activeRule.getConfigKey()).thenReturn(activeConfigRuleKey);
        when(activeRule.getRuleKey()).thenReturn("_" + activeConfigRuleKey);
      } else {
        throw new IllegalArgumentException("Unsupported active rule config key: " + activeConfigRuleKey);
      }
      builder.add(activeRule);
    }
    return builder.build();
  }

}
