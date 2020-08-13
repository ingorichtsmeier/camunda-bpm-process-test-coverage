package org.camunda.bpm.extension.process_test_coverage.extension;

import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.extension.process_test_coverage.junit.extension.ProcessEngineTestCoverageExtension;
import org.camunda.bpm.extension.process_test_coverage.rules.CoverageTestProcessConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ProcessEngineTestCoverageExtension.class)
public class JUnit5ExtensionTest {
  
  static ProcessEngine processEngine;
  
  @Test
  @Deployment(resources = CoverageTestProcessConstants.BPMN_PATH)
  public void testPathA() {
    Map<String, Object> variables = new HashMap<String, Object>();
    variables.put("path", "A");
    processEngine.getRuntimeService().startProcessInstanceByKey(CoverageTestProcessConstants.PROCESS_DEFINITION_KEY, variables);
  }

  @Test
  @Deployment(resources = CoverageTestProcessConstants.BPMN_PATH)
  public void testPathB() {
      Map<String, Object> variables = new HashMap<String, Object>();
      variables.put("path", "B");
      processEngine.getRuntimeService().startProcessInstanceByKey(CoverageTestProcessConstants.PROCESS_DEFINITION_KEY, variables);
  }
  
}
