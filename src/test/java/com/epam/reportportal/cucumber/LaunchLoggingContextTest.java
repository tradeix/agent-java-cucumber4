package com.epam.reportportal.cucumber;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.mockito.Mockito.*;

/**
 * @author <a href="mailto:ihar_kahadouski@epam.com">Ihar Kahadouski</a>
 */
public class LaunchLoggingContextTest {

	@Mock
	private ReportPortal reportPortal;

	@Mock
	private ListenerParameters listenerParameters;

	@Mock
	private Launch launch;

	@Test
	public void verifyLaunchLoggingContextInit() {
		com.epam.reportportal.cucumber.StepReporter stepReporter = new StepReporter() {
			@NotNull
			@Override
			protected ReportPortal buildReportPortal() {
				return reportPortal;
			}
		};

		when(reportPortal.getParameters()).thenReturn(listenerParameters);
		when(reportPortal.newLaunch(any())).thenReturn(launch);
		stepReporter.beforeLaunch();

		verify(launch, times(1)).start();
	}

	@Test
	public void verifyLaunchLoggingContextInitScenarioReporter() {
		com.epam.reportportal.cucumber.ScenarioReporter scenarioReporter = new ScenarioReporter() {
			@NotNull
			@Override
			protected ReportPortal buildReportPortal() {
				return reportPortal;
			}
		};

		when(reportPortal.getParameters()).thenReturn(listenerParameters);
		when(reportPortal.newLaunch(any())).thenReturn(launch);
		scenarioReporter.beforeLaunch();

		verify(launch, times(1)).start();
	}
}