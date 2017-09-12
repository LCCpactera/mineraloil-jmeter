package com.lithium.mineraloil.jmeter;

import com.lithium.mineraloil.jmeter.reports.CSVReport;
import com.lithium.mineraloil.jmeter.reports.CreateOrUpdateESMapping;
import com.lithium.mineraloil.jmeter.reports.JTLReport;
import com.lithium.mineraloil.jmeter.reports.SummaryReport;
import com.lithium.mineraloil.jmeter.test_elements.JMeterStep;
import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.apache.jmeter.JMeter;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.gui.TestPlanGui;
import org.apache.jmeter.engine.ClientJMeterEngine;
import org.apache.jmeter.engine.DistributedRunner;
import org.apache.jmeter.engine.JMeterEngine;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.report.config.ConfigurationException;
import org.apache.jmeter.report.dashboard.GenerationException;
import org.apache.jmeter.report.dashboard.ReportGenerator;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.Remoteable;
import org.apache.jmeter.samplers.SampleSaveConfiguration;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.RemoteThreadsListenerTestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.backend.BackendListener;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.jorphan.util.JOrphanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class JMeterRunner extends Observable {
    protected final Logger logger = LoggerFactory.getLogger(JMeterRunner.class);
    @Getter
    private final String testPlanName;
    @Getter
    private final String testPlanFileName;
    public CookieManager cookieManager;
    protected String jmeterBinDir;
    protected File jmeterProperties;
    protected StandardJMeterEngine jmeter;
    protected ListedHashTree testPlanTree;
    private TestPlan testPlan;
    private SummaryReport summaryResults;
    private ArrayList<JMeterStep> steps;
    ClientJMeterEngine clientJMeterEngine;
    protected StandardJMeterEngine jmeterRemote;
    private Properties extraProperties;

    public static final String JMETER_REPORT_OUTPUT_DIR_PROPERTY =
            "jmeter.reportgenerator.outputdir";

    public JMeterRunner(String testPlanName) {
        jmeter = new StandardJMeterEngine();

        jmeterBinDir = JMeterRunner.class.getClassLoader().getResource("jmeter").getPath();
        JMeterUtils.setJMeterHome(jmeterBinDir.toString());
        readProperties();
        JMeterUtils.initLocale();
        JMeterUtils.initLogging();
        testPlanTree = new ListedHashTree();
        this.testPlanName = testPlanName;
        this.testPlanFileName = testPlanName.toLowerCase().replaceAll("\\s+", "-");
        createTestPlan();
    }

    private void createTestPlan() {
        logger.info("Creating test plan:" + testPlanName);

        testPlan = getTestPlan();
        testPlanTree.add(testPlan);
    }

    public void addStep(JMeterStep step) {
        if (steps == null) steps = new ArrayList<>();
        steps.add(step);
    }

    public TestPlan getTestPlan() {
        TestPlan testPlan = new TestPlan();

        testPlan.setProperty(TestElement.TEST_CLASS, TestPlan.class.getName());
        testPlan.setProperty(TestElement.GUI_CLASS, TestPlanGui.class.getName());
        testPlan.setProperty(TestElement.ENABLED, true);
        testPlan.setProperty("CookieManager.check.cookies", false);

        testPlan.setFunctionalMode(false);
        testPlan.setSerialized(true);
        Arguments arguments = new Arguments();
        arguments.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.gui.HTTPArgumentsPanel");
        arguments.setProperty(TestElement.TEST_CLASS, "org.apache.jmeter.config.Arguments");
        arguments.setName("User Defined Variables");
        arguments.setProperty(TestElement.ENABLED, true);

        testPlan.setUserDefinedVariables(arguments);
        testPlan.setTestPlanClasspath("");
        return testPlan;
    }

    private void addTestSteps() {

        for (JMeterStep step : steps) {
            step.setOutputFilePath(String.format("%s/%s-", JMeterRunner.getOutputDirectory(), testPlanFileName));
            HashTree childHashTree = testPlanTree.add(testPlan, (Object) step.getTestElement());
            if (step.getSteps().size() > 0) addChildTestElements(childHashTree, step);
        }
    }

    // recursively create the child nodes
    private void addChildTestElements(HashTree hashTree, JMeterStep currentStep) {
        logger.info(String.format("Adding child steps for %s", currentStep.getTestElement().getName()));
        for (JMeterStep childStep : currentStep.getSteps()) {
            childStep.setOutputFilePath(String.format("%s/%s-", JMeterRunner.getOutputDirectory(), testPlanFileName));
            HashTree childHashTree = hashTree.add((Object) childStep.getTestElement());
            if (childStep.getSteps().size() > 0) addChildTestElements(childHashTree, childStep);
        }
    }

    private void addJTLResultsCollector() {
        ResultCollector resultCollector = new ResultCollector();
        resultCollector.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        resultCollector.setProperty(TestElement.TEST_CLASS, "org.apache.jmeter.reporters.ResultCollector");
        resultCollector.setProperty(TestElement.NAME, "View Results Tree");
        resultCollector.setProperty(TestElement.ENABLED, true);
        resultCollector.setProperty("ResultCollector.error_logging", false);
        SampleSaveConfiguration sampleSaveConfiguration = new SampleSaveConfiguration();
        sampleSaveConfiguration.setAsXml(true);
        sampleSaveConfiguration.setFieldNames(true);
        sampleSaveConfiguration.setResponseData(false);
        sampleSaveConfiguration.setResponseHeaders(false);
        sampleSaveConfiguration.setFileName(false);
        sampleSaveConfiguration.setSampleCount(true);
        sampleSaveConfiguration.setEncoding(true);
        sampleSaveConfiguration.setRequestHeaders(false);
        sampleSaveConfiguration.setMessage(true);
        sampleSaveConfiguration.setSamplerData(false);
        sampleSaveConfiguration.setHostname(true);
        sampleSaveConfiguration.setFieldNames(true);
        resultCollector.setSaveConfig(sampleSaveConfiguration);
        resultCollector.setProperty("filename", getFileName("jtl"));
        testPlanTree.add(testPlan, resultCollector);
    }


    private void addSummaryReport() {
        ResultCollector collector = new ResultCollector();
        collector.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.visualizers.SummaryReport");
        collector.setProperty(TestElement.TEST_CLASS, "org.apache.jmeter.visualizers.ResultCollector");
        collector.setProperty(TestElement.NAME, "summary");
        collector.setProperty(TestElement.ENABLED, true);
        collector.setSaveConfig(getSampleSaveConfiguration());
        collector.setFilename(getFileName("summary", "xml"));
        collector.setProperty("interval_grouping", 1000);
        collector.setProperty("graph_aggregated", false);
        collector.setProperty("include_checkbox_state", false);
        collector.setProperty("exclude_checkbox_state", false);
        testPlanTree.add(testPlan, collector);

    }

    // user the isReportable flag to generate a second jtl
    // that only has a subset of the test results
    private void createReportableJtl() {
        new JTLReport(getFileName("jtl")).createReportableResults(getFileName("reportable", "jtl"));
    }

    private void createReportableJtl(Boolean pageClassification) {
        if (pageClassification)
            new JTLReport(getFileName("jtl")).createReportableResultsWithPageClassification(getFileName("reportable", "jtl"));
        else
            createReportableJtl();
    }

    private void createReportableCSV() {
        new CSVReport(getFileName("csv")).createReportableResults(getFileName("reportable", "csv"));
    }

    public SummaryReport getSummaryResults() {
        if (summaryResults == null) summaryResults = new SummaryReport(getFileName("summary", "xml"));
        return summaryResults;
    }

    private ResultCollector getSamplesResultsCollector(String fileFormat) {

        ResultCollector resultCollector = new ResultCollector();
        resultCollector.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        resultCollector.setProperty(TestElement.TEST_CLASS, "org.apache.jmeter.reporters.ResultCollector");
        resultCollector.setProperty(TestElement.NAME, "View Results Tree For " + fileFormat);
        resultCollector.setProperty(TestElement.ENABLED, true);
        resultCollector.setProperty("ResultCollector.error_logging", false);
        SampleSaveConfiguration ssc = new SampleSaveConfiguration();
        ssc.setTime(true);
        ssc.setLatency(true);
        ssc.setTimestamp(true);
        ssc.setSuccess(true);
        ssc.setLabel(true);
        ssc.setCode(true);
        ssc.setMessage(true);
        ssc.setThreadName(true);
        ssc.setDataType(false);
        ssc.setEncoding(false);
        ssc.setAssertions(true);
        ssc.setSubresults(false);
        ssc.setResponseData(false);
        ssc.setSamplerData(false);

        if ("xml".equals(fileFormat))
            ssc.setAsXml(true);
        else {
            ssc.setAsXml(false);
        }
        ssc.setFieldNames(true);
        ssc.setResponseHeaders(false);
        ssc.setAssertionResultsFailureMessage(false);
        ssc.setBytes(true);
        ssc.setHostname(true);
        ssc.setThreadCounts(true);
        ssc.setSampleCount(true);
        ssc.saveUrl();
        ssc.setRequestHeaders(true);
        ssc.setResponseHeaders(true);
        resultCollector.setSaveConfig(ssc);
        resultCollector.setProperty("filename", getFileName(fileFormat));
        return resultCollector;
    }

    public void addCSVResultsCollector() {
        ResultCollector resultCollector = getSamplesResultsCollector("csv");
        testPlanTree.add(testPlan, resultCollector);
    }

    public void generateDashBoard() {
        File dashboardDir = new File(getOutputDirectory() + "/dashboard-reports/");

        if (dashboardDir.exists()) {
            try {
                FileUtils.deleteDirectory(dashboardDir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        dashboardDir.mkdir();

        JMeterUtils.setProperty(JMETER_REPORT_OUTPUT_DIR_PROPERTY, dashboardDir.getAbsolutePath());
        JMeterUtils.setProperty("jmeter.save.saveservice.assertion_results_failure_message", "false");

        logger.info("Setting property '" + JMETER_REPORT_OUTPUT_DIR_PROPERTY + "' to:'" + dashboardDir.getAbsolutePath() + "'");

        try {
/*            File csvResultsLogFile = new File(getOutputDirectory()+File.separator+this.getTestPlanFileName()+".csv");
            if (!csvResultsLogFile.exists()){
                try {
                    csvResultsLogFile.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } */
            JMeterUtils.setProperty("jmeter.save.saveservice.output_format", "csv");
            ReportGenerator reportGenerator = new ReportGenerator(getOutputDirectory() + File.separator + this.getTestPlanFileName() + "-reportable.csv", null);

            try {
                reportGenerator.generate();
            } catch (GenerationException e) {
                e.printStackTrace();
            }
        } catch (ConfigurationException e) {
            e.printStackTrace();
        }
        //JMeterUtils.setProperty("jmeter.save.saveservice.output_format","xml");
    }

    public void createJMX() {
        try {
            SaveService.saveTree(testPlanTree, new FileOutputStream(getFileName("jmx")));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void readProperties() {
        jmeterProperties = new File(jmeterBinDir + "/bin/jmeter.properties");
        if (!jmeterProperties.exists()) {
            throw new RuntimeException("Unable to locate file: " + jmeterProperties.getAbsolutePath());
        }
        JMeterUtils.loadJMeterProperties(jmeterProperties.getPath());

        if (extraProperties != null)
            JMeterUtils.getJMeterProperties().putAll(extraProperties);

    }

    public void addExtraJmeterProperties(Properties properties) {

        extraProperties = properties;

    }

    public static String getOutputDirectory() {
        String dir = ClassLoader.getSystemClassLoader().getSystemResource("").getPath() + "../jmeter";
        File file = new File(dir);
        if (!file.exists()) file.mkdir();
        return dir;
    }

    public CookieManager getCookieManager() {
        if (cookieManager == null) {
            cookieManager = new CookieManager();
            cookieManager.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.gui.CookiePanel");
            cookieManager.setProperty(TestElement.TEST_CLASS, "org.apache.jmeter.protocol.http.control.CookieManager");
            cookieManager.setProperty(TestElement.ENABLED, true);
            cookieManager.setName("HTTP Cookie Manager");
            cookieManager.setClearEachIteration(true);
            cookieManager.setCookiePolicy("compatibility");
            cookieManager.setImplementation("org.apache.jmeter.protocol.http.control.HC4CookieHandler");
            testPlanTree.add(testPlan, cookieManager);
        }
        return cookieManager;
    }

    public String getFileName(String name, String extension) {
        return String.format("%s/%s-%s.%s", JMeterRunner.getOutputDirectory(), testPlanFileName, name, extension);
    }

    public String getFileName(String extension) {
        return String.format("%s/%s.%s", JMeterRunner.getOutputDirectory(), testPlanFileName, extension);
    }

    private SampleSaveConfiguration getSampleSaveConfiguration() {
        SampleSaveConfiguration ssc = new SampleSaveConfiguration();
        ssc.setTime(true);
        ssc.setLatency(true);
        ssc.setTimestamp(true);
        ssc.setSuccess(true);
        ssc.setLabel(true);
        ssc.setCode(true);
        ssc.setMessage(true);
        ssc.setThreadName(true);
        ssc.setDataType(false);
        ssc.setEncoding(false);
        ssc.setAssertions(true);
        ssc.setSubresults(false);
        ssc.setResponseData(false);
        ssc.setSamplerData(false);
        ssc.setAsXml(true);
        ssc.setFieldNames(true);
        ssc.setResponseHeaders(false);
        ssc.setAssertionResultsFailureMessage(false);
        ssc.setBytes(true);
        ssc.setHostname(true);
        ssc.setThreadCounts(true);
        ssc.setSampleCount(true);
        ssc.saveUrl();
        ssc.setRequestHeaders(true);
        ssc.setResponseHeaders(true);
        return ssc;
    }

    public void run() {
        getCookieManager();
        addTestSteps();
        addJTLResultsCollector();
        addSummaryReport();
        addCSVResultsCollector();
        jmeter.configure(testPlanTree);
        createJMX();
        updateObserversStart();

        jmeter.run();

        updateObserversStop();
        createReportableJtl(true);
        createReportableCSV();
        generateDashBoard();
        jmeter.exit();
    }

    public void runForChrome() {
        getCookieManager();
        addTestSteps();
        addJTLResultsCollector();
        addSummaryReport();
        addCSVResultsCollector();
        jmeter.configure(testPlanTree);
        createJMX();
        updateObserversStart();

        jmeter.run();

        updateObserversStop();
        //createReportableJtl(true);
        createReportableCSV();
        generateDashBoard();
        jmeter.exit();
    }

    private void updateObserversStart() {
        setChanged();
        JMeterUpdate update = new JMeterUpdate();
        update.setTestPlanName(testPlanName);
        update.setState(JMeterStatus.STARTED);
        notifyObservers(update);
    }

    private void updateObserversStop() {
        setChanged();
        JMeterUpdate update = new JMeterUpdate();
        update.setTestPlanName(testPlanName);
        update.setState(JMeterStatus.STOPPED);
        notifyObservers(update);
    }

    public void remoteRun(List<String> remoteHosts) {


        getCookieManager();
        addTestSteps();
        addJTLResultsCollector();
        addSummaryReport();
        addCSVResultsCollector();
        this.addRemoteTestListener();

        createJMX();

        DistributedRunner distributedRunner = new DistributedRunner();
        distributedRunner.setStdout(System.out);
        distributedRunner.setStdErr(System.err);


        distributedRunner.init(remoteHosts, testPlanTree);
        distributedRunner.start();

        String reaperRE = JMeterUtils.getPropDefault("rmi.thread.name", "^RMI Reaper$");
        Thread reaper = null;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (name.matches(reaperRE)) {
                reaper = t;
            }
        }

        if (reaper != null) {
            while (reaper.getState() == Thread.State.WAITING) {
            }
        }

        updateObserversStop();
        createReportableJtl(true);
        createReportableCSV();
        generateDashBoard();
        distributedRunner.exit(remoteHosts);
    }

    public void addRemoteTestListener() {
        testPlanTree.add(testPlanTree.getArray()[0], new RemoteThreadsListenerTestElement());

        ListenToTest listener = new ListenToTest(null, null);
        testPlanTree.add(testPlanTree.getArray()[0], listener);
    }

    public void addElasticSearchListener(String esClusterName, String runId, String elasticSearchClusterHost, int port) {
        BackendListener backendListener = new BackendListener();
        backendListener.setClassname("com.lithium.mineraloil.jmeter.reports.ElasticSearchListener");
        Arguments arguments = new Arguments();
        arguments.addArgument("elasticsearchCluster", elasticSearchClusterHost);
        arguments.addArgument("elasticsearchPort", Integer.toString(port));
        arguments.addArgument("indexName", "jmeter_v1");
        arguments.addArgument("sampleType", "SampleResult");
        arguments.addArgument("dateTimeAppendFormat", "-yyyy-MM");
        arguments.addArgument("normalizedTime", "2015-01-01 00:00:00.000-00:00");
        arguments.addArgument("runId", runId);
        arguments.addArgument("clusterName", esClusterName);

        backendListener.setArguments(arguments);

        testPlanTree.add(testPlanTree.getArray()[0], backendListener);

    }


    public void addElasticSearchMapping(String esClusterName, String esClusterHost, int port) {

        CreateOrUpdateESMapping createOrUpdateESMapping = new CreateOrUpdateESMapping(esClusterName, esClusterHost, port);
        createOrUpdateESMapping.createMapping();
    }

    static class ListenToTest implements TestStateListener, Runnable, Remoteable {
        private final AtomicInteger started = new AtomicInteger(0); // keep track of remote tests

        //NOT YET USED private JMeter _parent;

        private final List<JMeterEngine> engines;

        /**
         * @param unused  JMeter unused for now
         * @param engines List<JMeterEngine>
         */
        public ListenToTest(JMeter unused, List<JMeterEngine> engines) {
            //_parent = unused;
            this.engines = engines;
        }

        @Override
        public void testEnded(String host) {
            long now = System.currentTimeMillis();
            System.out.println("Finished remote host: " + host + " (" + now + ")");
            if (started.decrementAndGet() <= 0) {
                Thread stopSoon = new Thread(this);
                stopSoon.start();
            }
        }

        @Override
        public void testEnded() {
            long now = System.currentTimeMillis();
            System.out.println("Tidying up ...    @ " + new Date(now) + " (" + now + ")");
            System.out.println("... end of run");
            checkForRemainingThreads();
        }

        @Override
        public void testStarted(String host) {
            started.incrementAndGet();
            long now = System.currentTimeMillis();
            System.out.println("Started remote host:  " + host + " (" + now + ")");
        }

        @Override
        public void testStarted() {
            long now = System.currentTimeMillis();
            System.out.println(JMeterUtils.getResString("running_test") + " (" + now + ")");//$NON-NLS-1$
        }

        /**
         * This is a hack to allow listeners a chance to close their files. Must
         * implement a queue for sample responses tied to the engine, and the
         * engine won't deliver testEnded signal till all sample responses have
         * been delivered. Should also improve performance of remote JMeter
         * testing.
         */
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            System.out.println("Tidying up remote @ " + new Date(now) + " (" + now + ")");
            if (engines != null) { // it will be null unless remoteStop = true
                System.out.println("Exitting remote servers");
                for (JMeterEngine e : engines) {
                    e.exit();
                }
            }
            try {
                TimeUnit.SECONDS.sleep(5); // Allow listeners to close files
            } catch (InterruptedException ignored) {
            }
            ClientJMeterEngine.tidyRMI(LoggingManager.getLoggerForClass());
            System.out.println("... end of run");
            checkForRemainingThreads();
        }

        /**
         * Runs daemon thread which waits a short while;
         * if JVM does not exit, lists remaining non-daemon threads on stdout.
         */
        private void checkForRemainingThreads() {
            // This cannot be a JMeter class variable, because properties
            // are not initialised until later.
            final int REMAIN_THREAD_PAUSE =
                    JMeterUtils.getPropDefault("jmeter.exit.check.pause", 2000); // $NON-NLS-1$

            if (REMAIN_THREAD_PAUSE > 0) {
                Thread daemon = new Thread() {
                    @Override
                    public void run() {
                        try {
                            TimeUnit.MILLISECONDS.sleep(REMAIN_THREAD_PAUSE); // Allow enough time for JVM to exit
                        } catch (InterruptedException ignored) {
                        }
                        // This is a daemon thread, which should only reach here if there are other
                        // non-daemon threads still active
                        System.out.println("The JVM should have exitted but did not.");
                        System.out.println("The following non-daemon threads are still running (DestroyJavaVM is OK):");
                        JOrphanUtils.displayThreads(false);
                    }

                };
                daemon.setDaemon(true);
                daemon.start();
            } else if (REMAIN_THREAD_PAUSE <= 0) {
                System.out.println("jmeter.exit.check.pause is <= 0, JMeter won't check for unterminated non-daemon threads");
            }
        }
    }
}
