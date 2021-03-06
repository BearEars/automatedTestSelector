package org.jenkinsci.plugins.automatedTestSelector;

import com.google.common.collect.ImmutableSet;

import hudson.AbortException;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;

import hudson.model.*;

import hudson.scm.ChangeLogSet.Entry;

import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TabulatedResult;
import hudson.tasks.test.TestResult;
import hudson.util.FormValidation;

import org.apache.commons.io.Charsets;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.*;
import java.util.ArrayList;

/**
 * @author Taylor Ecton
 */

public class RegressionTestSelector extends Builder {

    private static final ImmutableSet<Result> RESULTS_TO_CONSIDER = ImmutableSet.of(Result.SUCCESS, Result.UNSTABLE, Result.FAILURE);
    private static final String ANNOTATION_START_1 = "@SuiteClasses({";
    private static final String ANNOTATION_START_2 = "@Suite.SuiteClasses({";
    private static final String ANNOTATION_END = "})";

    private static final String HANDOFF_FILE = "handoff.txt";

    private final int failureWindow;
    private final int executionWindow;

    private final String testReportDir;
    private final String testSuiteFile;

    private final boolean useDepAnalysis;
    private final String udbPath;

    @DataBoundConstructor
    public RegressionTestSelector(int failureWindow,
                                  int executionWindow,
                                  String testReportDir,
                                  String testSuiteFile,
                                  boolean useDepAnalysis,
                                  String udbPath) {
        this.executionWindow = executionWindow;
        this.failureWindow = failureWindow;

        this.testReportDir = testReportDir;
        this.testSuiteFile = testSuiteFile;

        this.useDepAnalysis = useDepAnalysis;
        this.udbPath = udbPath;
    }

    /**
     * Getter functions
     */
    public int getExecutionWindow() {
        return executionWindow;
    }

    public int getFailureWindow() {
        return failureWindow;
    }

    public String getTestReportDir() {
        return testReportDir;
    }

    public String getTestSuiteFile() {
        return testSuiteFile;
    }

    public boolean getUseDepAnalysis() {
        return useDepAnalysis;
    }

    public String getUdbPath() {
        return udbPath;
    }

    /**
     * main function of the regression test selector
     */
    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        listener.getLogger().println("Running regression test selector...");
        listener.getLogger().println("Failure window is set to: " + failureWindow);
        listener.getLogger().println("Execution window is set to: " + executionWindow);

        FilePath workspace = build.getWorkspace();
        if (workspace == null)
            throw new AbortException("No workspace");

        FilePath reportDir = workspace.child(testReportDir);
        reportDir.deleteContents();

        ArrayList<String> linesForFile = new ArrayList<>();
        ArrayList<String> allTests = getAllTests(workspace, linesForFile);
        ArrayList<String> selectedTests = selectTests(build, listener, allTests);

        if (useDepAnalysis) {
            selectedTests = doDependencyAnalysis(build, listener, selectedTests);
        }

        listener.getLogger().println(selectedTests.size() + " out of " + allTests.size() + " selected for execution");

        buildTestSuiteFile(workspace, selectedTests, linesForFile);

        return true;
    }

    /**
     * Uses SciTools Understand database for static code analysis and determines which tests are relevant to
     * changes made in version control
     *
     * @param build The current build
     * @param listener BuildListener used for logging to Jenkins console output
     * @param selectedTests list of tests selected for execution
     * @return List of tests within selected tests that are relevant to the current code changes
     */
    private ArrayList<String> doDependencyAnalysis(AbstractBuild<?,?> build,
                                                   BuildListener listener,
                                                   ArrayList<String> selectedTests)
            throws IOException, InterruptedException {
        // ------------ DEPENDENCY ANALYSIS CLASS MOVED TO STAND-ALONE PROGRAM -----------------------------
        // ------------ due to bug that has not yet been resolved... ---------------------------------------
        // DependencyAnalysis dependencyAnalysis = new DependencyAnalysis(udbPath, workspacePath, listener);

        ArrayList<String> relevantTests = new ArrayList<>();
        ArrayList<String> allChangedFiles = new ArrayList<>();
        ArrayList<String> changedSourceFiles = new ArrayList<>();
        ArrayList<String> dependentModules = new ArrayList<>();

        listener.getLogger().println("**----------------------------------**"); // <-- for debugging
        listener.getLogger().println("Running dependency analysis code..."); // <-- for debugging

        for (Entry entry : build.getChangeSet()) {
            if (entry.getAffectedPaths() != null)
                allChangedFiles.addAll(entry.getAffectedPaths());
        }

        if (!allChangedFiles.isEmpty()) {
            listener.getLogger().println("-------------------------------"); // <-- for debugging
            listener.getLogger().println("All changed files: "); // <-- for debugging
            for (String file : allChangedFiles) {
                listener.getLogger().println(file); // <-- for debugging
                if (file.contains(".java")) {
                    String[] pathComponents = file.split("/");
                    file = pathComponents[pathComponents.length - 1];
                    file = file.replace(".java", "");
                    changedSourceFiles.add(file);
                }
            }
            listener.getLogger().println("-------------------------------"); // <-- for debugging

            if (!changedSourceFiles.isEmpty()) {
                // --------------- DEPENDENCY ANALYSIS CLASS MOVED TO STAND-ALONE PROGRAM -------
                // --------------- due to issue opening Understand database more than once ------
                // dependentModules = dependencyAnalysis.getDependentModules(changedSourceFiles);

                runDependencyAnalysisJava(build, listener, changedSourceFiles, dependentModules);

                listener.getLogger().println("All dependent files: "); // <-- for debugging
                for (String file : dependentModules) {
                    listener.getLogger().println(file);
                    file += ".class";
                    if (selectedTests.contains(file)) {
                        relevantTests.add(file);
                    }
                }
            } else {
                listener.getLogger().println("No changed source code files. Utilizing all tests for prioritization.");
                relevantTests = selectedTests;
            }
            listener.getLogger().println("**----------------------------------**"); // <-- for debugging
        }

        if (relevantTests.isEmpty()){
            listener.getLogger().println("List of relevant tests is empty. Tests may be unrelated to changes.");
            listener.getLogger().println("Using all tests in Test Suite File");
            relevantTests = selectedTests;
        }

        return relevantTests;
    }

    private void runDependencyAnalysisJava(AbstractBuild<?,?> build,
                                           BuildListener listener,
                                           ArrayList<String> changedSourceFiles,
                                           ArrayList<String> dependentModules)
            throws IOException, InterruptedException {

        // wokspacePath is the absolute path of the build workspace for this Jenkins job
        String workspacePath;
        try {
            workspacePath = build.getWorkspace().getRemote();
        } catch (NullPointerException e) {
            throw new AbortException("getRemote returned null");
        }
        // concatenate workspacePath and HANDOFF_FILE to get path of handoff file
        String handoffPath = workspacePath + "/" + HANDOFF_FILE;

        // listener.getLogger().println("handoffpath = " + handoffPath); // <-- for debugging

        // create the handoff file; if there is an old one, delete it first
        try {
            File file = new File(handoffPath);
            if (file.exists()) {

                listener.getLogger().println("Deleting old handoff file..."); // <-- for debugging

                if (file.delete()) listener.getLogger().println("File deleted.");
            }

            OutputStream outputStream = new FileOutputStream(handoffPath);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, Charsets.UTF_8);
            PrintWriter printWriter = new PrintWriter(outputStreamWriter);

            listener.getLogger().println("Writing new handoff file..."); // <-- for debugging

            // write each changed source file to the handoff file
            for (String sourceFile : changedSourceFiles)
                printWriter.println(sourceFile);

            printWriter.close();
        } catch (IOException exception) {
            listener.getLogger().println(exception.getMessage());
        }

        // command is the shell command to run DependencyAnalysis program
        String command = "java DependencyAnalysis " + udbPath + " " + workspacePath + " " + handoffPath;

        // listener.getLogger().println("command = " + command); // <-- for debugging

        // run command using exec; output is redirected to listener.getLogger() for viewing in Jenkins
        Process dependencyAnalysis = Runtime.getRuntime().exec(command);
        String output;
        BufferedReader depAnalysisReader = new BufferedReader(
                new InputStreamReader(dependencyAnalysis.getInputStream(), Charsets.UTF_8) );
        while ((output = depAnalysisReader.readLine()) != null) {
            listener.getLogger().println(output);
        }
        depAnalysisReader.close();

        // make sure dependencyAnalysis process terminates before proceeding
        dependencyAnalysis.waitFor();

        // try to read information from the handoff file; should now contain information from
        // dependency analysis program
        try {
            InputStream inputStream;
            try {
                inputStream = build.getWorkspace().child(HANDOFF_FILE).read();
            } catch (NullPointerException e) {
                throw new AbortException("inputStream is null");
            }
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            String line;
            while((line = bufferedReader.readLine()) != null)
                dependentModules.add(line);

            bufferedReader.close();
            inputStreamReader.close();
            inputStream.close();
        } catch (IOException exception) {
            listener.getLogger().println(exception.getMessage());
        }
    }


    /**
     * Gets all tests from the test suite file
     *
     * @param workspace FilePath for the current project workspace
     * @param linesForFile lines from the test suite file to be used to rebuild file later
     * @return a list of all tests found in the test suite file
     */
    private ArrayList<String> getAllTests(FilePath workspace, ArrayList<String> linesForFile)
            throws IOException, InterruptedException {

        ArrayList<String> allTests = new ArrayList<>();

        // try to read the file, read in all of the test names and add them to the list
        try (InputStream inputStream = workspace.child(testSuiteFile).read();
             InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                linesForFile.add(line);
                if (line.trim().equals(ANNOTATION_START_1) || line.trim().equals(ANNOTATION_START_2)) {
                    line = bufferedReader.readLine();
                    while (line != null && !line.trim().equals(ANNOTATION_END)) {
                        line = line.trim();
                        if (line.contains(","))
                            line = line.replace(",", "");
                        allTests.add(line);
                        line = bufferedReader.readLine();
                    }
                }
            }
            bufferedReader.close();
        }

        return allTests;
    }

    /**
     * Selects tests for execution
     *
     * @param build Current build
     * @param listener BuildListener used for logging to Jenkins console output
     * @param tests List of all tests found in test suite file
     *
     * @return List of tests selected for execution
     */
    private ArrayList<String> selectTests(Run<?, ?> build, TaskListener listener, ArrayList<String> tests) {
        ArrayList<String> selectedTests = new ArrayList<>();
        ArrayList<String> foundTests = new ArrayList<>();

        // continue iterating until i reaches failureWindow or executionWindow, whichever is larger
        for (int i = 0; i < this.getFailureWindow() || i < this.getExecutionWindow(); i++) {
            build = build.getPreviousBuild();

            if (build == null) break;
            if (!RESULTS_TO_CONSIDER.contains(build.getResult())) continue; // build failed = no test results

            AbstractTestResultAction testResultAction = build.getAction(AbstractTestResultAction.class);
            if (testResultAction == null) continue;

            Object object = testResultAction.getResult();
            if (object instanceof TestResult) {
                TestResult result = (TestResult) object;
                ArrayList<String> testsFailedThisBuild = new ArrayList<>();

                Boolean withinExecutionWindow = i < this.getExecutionWindow();
                Boolean withinFailureWindow = i < this.getFailureWindow();
                collect(result, testsFailedThisBuild, foundTests, withinExecutionWindow, withinFailureWindow);

                // failing tests within failure window should be selected; don't add duplicates
                for (String testName : testsFailedThisBuild) {
                    if (!selectedTests.contains(testName))
                        selectedTests.add(testName);
                }
            }
        }

        // tests not found have not been executed within execution window and should be selected
        for (String test : tests) {
            if (!foundTests.contains(test))
                selectedTests.add(test);
        }

        return selectedTests;
    }

    /**
     * Rewrites the test suite file to include only selected tests
     *
     * @param workspace FilePath of current project workspace
     * @param selectedTests List of tests selected for execution
     * @param linesForFile List containing lines from the test suite file
     */
    private void buildTestSuiteFile(FilePath workspace,
                                    ArrayList<String> selectedTests,
                                    ArrayList<String> linesForFile)
            throws IOException, InterruptedException {

        try (OutputStream osSuiteFile = workspace.child(testSuiteFile).write();
             OutputStreamWriter oswSuiteFile = new OutputStreamWriter(osSuiteFile, Charsets.UTF_8);
             PrintWriter pwSuiteFile = new PrintWriter(oswSuiteFile)) {

            for (String line : linesForFile) {
                pwSuiteFile.println(line);

                if (line.equals(ANNOTATION_START_1) || line.equals(ANNOTATION_START_2)) {
                    String testName;
                    for (int i = 0; i < selectedTests.size() - 1; i++) {
                        testName = selectedTests.get(i);
                        pwSuiteFile.println(testName + ",");
                    }
                    testName = selectedTests.get(selectedTests.size()-1);
                    pwSuiteFile.println(testName);
                    pwSuiteFile.println(ANNOTATION_END);
                }
            }

            pwSuiteFile.close();
        }
    }

    /**
     * Collects tests into appropriate lists
     *
     * @param testResult TestResult from a build
     * @param failed List of tests that have been found to fail
     * @param found List of all found tests
     * @param withinExWindow Boolean representing if build is withinn execution window
     * @param withinFailWindow Boolean representing if build is within failure window
     */
    static private void collect(TestResult testResult, ArrayList<String> failed, ArrayList<String> found,
                                Boolean withinExWindow, Boolean withinFailWindow) {
        if (testResult instanceof ClassResult) {
            ClassResult classResult = (ClassResult) testResult;
            String className;
            String pkgName = classResult.getParent().getName();

            if (pkgName.equals("(root)"))
                pkgName = "";
            else
                pkgName += '.';
            className = pkgName + classResult.getName() + ".class";

            if (withinExWindow && !found.contains(className)) // don't add if outside of execution window
                found.add(className);

            if (withinFailWindow && classResult.getFailCount() > 0) // don't add if outside of failure window
                failed.add(className);

            return; // no need to go deeper
        }
        if (testResult instanceof TabulatedResult) {
            TabulatedResult tabulatedResult = (TabulatedResult) testResult;
            for (TestResult child : tabulatedResult.getChildren()) {
                collect(child, failed, found, withinExWindow, withinFailWindow);
            }
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckExecutionWindow(@QueryParameter String value)
                throws IOException, ServletException {
            try {
                int input = Integer.parseInt(value);
                if (input >= 0)
                    return FormValidation.ok();
                else
                    return FormValidation.error("Execution window must be a postive number.");
            } catch (NumberFormatException e) {
                return FormValidation.error("Execution window must be a number.");
            }
        }

        public FormValidation doCheckFailureWindow(@QueryParameter String value)
                throws IOException, ServletException {
            try {
                int input = Integer.parseInt(value);
                if (input >= 0)
                    return FormValidation.ok();
                else
                    return FormValidation.error("Failure window must be a postive number.");
            } catch (NumberFormatException e) {
                return FormValidation.error("Failure window must be a number.");
            }
         }

        public FormValidation doCheckTestSuiteFile(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("You must set the path of the test suite file for the project.");

            return FormValidation.ok();
        }

        public FormValidation doCheckTestReportDir(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("You must set the directory containing test results.");

            return FormValidation.ok();
        }

        public FormValidation doCheckUnderstandDatabasePath(@QueryParameter String understandDatabasePath,
                                                            @QueryParameter Boolean useDependencyAnalysis)
                throws IOException, ServletException {

            if (useDependencyAnalysis) {
                if (understandDatabasePath.length() == 0)
                    return FormValidation.error("To use dependency analysis, you must set this value.");
            }

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // this builder can be used with all project types
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Regression Test Selector";
        }
    }
}
