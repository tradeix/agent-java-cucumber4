package com.epam.reportportal.cucumber.integration;

import com.epam.reportportal.cucumber.StepReporter;
import com.epam.reportportal.service.ReportPortal;
import org.jetbrains.annotations.NotNull;

public class TestStepReporter extends StepReporter {
	public static final ThreadLocal<ReportPortal> RP = new ThreadLocal<>();

	@NotNull
	@Override
	protected ReportPortal buildReportPortal() {
		return RP.get();
	}
}
