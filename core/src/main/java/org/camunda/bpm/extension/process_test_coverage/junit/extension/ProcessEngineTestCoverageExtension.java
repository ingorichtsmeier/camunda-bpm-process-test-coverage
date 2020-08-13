package org.camunda.bpm.extension.process_test_coverage.junit.extension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.event.EventHandler;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.ProcessEngineExtension;
import org.camunda.bpm.extension.process_test_coverage.junit.rules.CoverageTestRunState;
import org.camunda.bpm.extension.process_test_coverage.junit.rules.CoverageTestRunStateFactory;
import org.camunda.bpm.extension.process_test_coverage.junit.rules.DefaultCoverageTestRunStateFactory;
import org.camunda.bpm.extension.process_test_coverage.listeners.CompensationEventCoverageHandler;
import org.camunda.bpm.extension.process_test_coverage.listeners.FlowNodeHistoryEventHandler;
import org.camunda.bpm.extension.process_test_coverage.listeners.PathCoverageParseListener;
import org.camunda.bpm.extension.process_test_coverage.model.AggregatedCoverage;
import org.camunda.bpm.extension.process_test_coverage.model.ClassCoverage;
import org.camunda.bpm.extension.process_test_coverage.model.MethodCoverage;
import org.camunda.bpm.extension.process_test_coverage.util.CoverageReportUtil;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class ProcessEngineTestCoverageExtension extends ProcessEngineExtension 
    implements BeforeAllCallback, AfterAllCallback {
  
  private static Logger logger = Logger.getLogger(ProcessEngineTestCoverageExtension.class.getCanonicalName());
  
  /**
   * The state of the current run (class and current method).
   */
  private CoverageTestRunState coverageTestRunState;

  /**
   * Controls run state initialization.
   * {@see #initializeRunState(Description)}
   */
  private boolean firstRun = true;

  /**
   * Log class and test method coverages?
   */
  private boolean detailedCoverageLogging = false;

  /**
   * Is method coverage handling needed?
   */
  private boolean handleTestMethodCoverage = true;

  /**
   *  Is class coverage handling needed?
   */
  private boolean handleClassCoverage = true;

  /**
   *  coverageTestRunStateFactory. Can be changed for aggregated/suite coverage check
   */
  private CoverageTestRunStateFactory coverageTestRunStateFactory = new DefaultCoverageTestRunStateFactory();

  /**
   * Matchers to be asserted on the class coverage percentage.
   */
  private Collection<Matcher<Double>> classCoverageAssertionMatchers = new LinkedList<Matcher<Double>>();

  /**
   * Matchers to be asserted on the individual test method coverages.
   */
  private Map<String, Collection<Matcher<Double>>> testMethodNameToCoverageMatchers = new HashMap<String, Collection<Matcher<Double>>>();

  /**
   * A list of process definition keys excluded from the test run.
   */
  private List<String> excludedProcessDefinitionKeys;

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    logger.info("entering beforeAll");
    initialize(context);
  }

  @Override
  public void beforeTestExecution(ExtensionContext context) throws Exception {
    logger.info("entering beforeTestExecution");
    super.beforeTestExecution(context);
    
    initialize(context);
  }

  private void initialize(ExtensionContext context) {
    if (processEngine == null) {
      super.initializeProcessEngine();
    }
    
    initializeRunState(context);
    
    initializeMethodCoverage(context);
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    if (handleClassCoverage) {
        handleClassCoverage(context);
    }
  }
  
  @Override
  public void afterTestExecution(ExtensionContext context) throws Exception {
    if (handleTestMethodCoverage) {
      handleTestMethodCoverage(context);
    }
    
    super.afterTestExecution(context);
  }

  /**
   * Initialize the coverage run state depending on the rule annotations and
   * notify the state of the current test name.
   * 
   * @param context
   */
  private void initializeRunState(final ExtensionContext context) {

      // Initialize new state once on @ClassRule run or on every individual
      // @Rule run
      if (firstRun) {
        
          String testClassName = context.getTestClass().map(Class::getName).orElse(null);
          coverageTestRunState = coverageTestRunStateFactory.create(testClassName, excludedProcessDefinitionKeys);

          initializeListenerRunState();

          firstRun = false;
      }

      String methodName = context.getTestMethod().map(Method::getName).orElse(null);
      coverageTestRunState.setCurrentTestMethodName(methodName);

  }

  /**
   * Sets the test run state for the coverage listeners. logging.
   * {@see ProcessCoverageInMemProcessEngineConfiguration}
   */
  private void initializeListenerRunState() {

      final ProcessEngineConfigurationImpl processEngineConfiguration = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();

      // Configure activities listener

      final FlowNodeHistoryEventHandler historyEventHandler = (FlowNodeHistoryEventHandler) processEngineConfiguration.getHistoryEventHandler();
      historyEventHandler.setCoverageTestRunState(coverageTestRunState);

      // Configure sequence flow listener

      final List<BpmnParseListener> bpmnParseListeners = processEngineConfiguration.getCustomPostBPMNParseListeners();

      for (BpmnParseListener parseListener : bpmnParseListeners) {

          if (parseListener instanceof PathCoverageParseListener) {

              final PathCoverageParseListener listener = (PathCoverageParseListener) parseListener;
              listener.setCoverageTestRunState(coverageTestRunState);
          }
      }

      // Compensation event handler

      final EventHandler compensationEventHandler = processEngineConfiguration.getEventHandler("compensate");
      if (compensationEventHandler != null && compensationEventHandler instanceof CompensationEventCoverageHandler) {

          final CompensationEventCoverageHandler compensationEventCoverageHandler = (CompensationEventCoverageHandler) compensationEventHandler;
          compensationEventCoverageHandler.setCoverageTestRunState(coverageTestRunState);

      } else {
          logger.warning("CompensationEventCoverageHandler not registered with process engine configuration!"
                  + " Compensation boundary events coverage will not be registered.");
      }

  }

  /**
   * Initialize the current test method coverage.
   * 
   * @param context
   */
  private void initializeMethodCoverage(ExtensionContext context) {

      // Not a @ClassRule run and deployments present
      if (deploymentId != null) {
        logger.info("DeploymentId is " + deploymentId);

          final List<ProcessDefinition> deployedProcessDefinitions = processEngine.getRepositoryService().createProcessDefinitionQuery().deploymentId(
              deploymentId).list();

          final List<ProcessDefinition> relevantProcessDefinitions = new ArrayList<ProcessDefinition>();
          for (ProcessDefinition definition: deployedProcessDefinitions) {
              if (!isExcluded(definition)) {
                  relevantProcessDefinitions.add(definition);
              }
          }

          coverageTestRunState.initializeTestMethodCoverage(processEngine,
                  deploymentId,
                  relevantProcessDefinitions,
                  context.getTestMethod().map(Method::getName).orElse(null));

      } else {
        logger.info("no method coverage initialized");
      }
  }

  private boolean isExcluded(ProcessDefinition processDefinition) {
    if (excludedProcessDefinitionKeys != null) {
        return excludedProcessDefinitionKeys.contains(processDefinition.getKey());
    }
    return false;
  }
  /**
   * Logs and asserts the test method coverage and creates a graphical report.
   * 
   * @param context
   */
  private void handleTestMethodCoverage(ExtensionContext context) {

      // Do test method coverage only if deployments present
      final Deployment methodDeploymentAnnotation = context.getTestMethod().get().getAnnotation(Deployment.class);
      final Deployment classDeploymentAnnotation = context.getTestClass().get().getAnnotation(Deployment.class);
      final boolean testMethodHasDeployment = methodDeploymentAnnotation != null || classDeploymentAnnotation != null;

      if (testMethodHasDeployment) {

          final String testName = context.getTestMethod().get().getName();
          final MethodCoverage testCoverage = coverageTestRunState.getTestMethodCoverage(testName);

          double coveragePercentage = testCoverage.getCoveragePercentage();

          // Log coverage percentage
          logger.info(testName + " test method coverage is " + coveragePercentage);

          logCoverageDetail(testCoverage);

          // Create graphical report
          CoverageReportUtil.createCurrentTestMethodReport(processEngine, coverageTestRunState);

          if (testMethodNameToCoverageMatchers.containsKey(testName)) {

              assertCoverage(coveragePercentage, testMethodNameToCoverageMatchers.get(testName));

          }

      }
  }
  
  /**
   * If the rule is a @ClassRule log and assert the coverage and create a
   * graphical report. For the class coverage to work all the test method
   * deployments have to be equal.
   * 
   * @param context
   */
  private void handleClassCoverage(ExtensionContext context) {

          final ClassCoverage classCoverage = coverageTestRunState.getClassCoverage();

          // Make sure the class coverage deals with the same deployments for
          // every test method
          classCoverage.assertAllDeploymentsEqual();

          final double classCoveragePercentage = classCoverage.getCoveragePercentage();

          // Log coverage percentage
          logger.info(
                  coverageTestRunState.getTestClassName() + " test class coverage is: " + classCoveragePercentage);

          logCoverageDetail(classCoverage);

          // Create graphical report
          CoverageReportUtil.createClassReport(processEngine, coverageTestRunState);

          assertCoverage(classCoveragePercentage, classCoverageAssertionMatchers);

  }

  
  
  /**
   * Logs the string representation of the passed coverage object.
   * 
   * @param coverage
   */
  private void logCoverageDetail(AggregatedCoverage coverage) {

      if (logger.isLoggable(Level.FINE) || isDetailedCoverageLogging()) {
          logger.log(Level.INFO, coverage.toString());
      }
  }

  public boolean isDetailedCoverageLogging() {
    return detailedCoverageLogging;
  }
  
  private void assertCoverage(double coverage, Collection<Matcher<Double>> matchers) {

    for (Matcher<Double> matcher : matchers) {

        MatcherAssert.assertThat(coverage, matcher);
    }

  }

  public static ProcessEngineTestCoverageExtension builder() {
    return new ProcessEngineTestCoverageExtension();
  }

  @Override
  public ProcessEngineTestCoverageExtension build() {
    // TODO Auto-generated method stub
    super.build();
    return this;
  }

  @Override
  public ProcessEngineTestCoverageExtension configurationResource(String configurationResource) {
    super.configurationResource(configurationResource);
    return this; 
  }
  
  
  
}
