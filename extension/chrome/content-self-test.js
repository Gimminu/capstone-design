function isChungmaruFilterLabPage() {
  return Boolean(
    document.documentElement?.dataset?.chungmaruFilterLab === "true" ||
    ((location.hostname === "127.0.0.1" || location.hostname === "localhost") &&
      location.port === "4178")
  );
}

function setLabSelfTestOutput(value) {
  const output = document.getElementById("self-test-output");
  if (!output) {
    return;
  }

  output.textContent =
    typeof value === "string"
      ? value
      : JSON.stringify(value, null, 2);
}

async function appendSelfTestHistory(entry) {
  if (!entry || typeof entry !== "object") {
    return;
  }

  const stored = await safeStorageLocalGet(["lastSelfTestHistory"]);
  const currentHistory = Array.isArray(stored.lastSelfTestHistory)
    ? stored.lastSelfTestHistory
    : [];
  const nextHistory = [entry, ...currentHistory].slice(0, MAX_SELF_TEST_HISTORY);
  await safeStorageLocalSet({ lastSelfTestHistory: nextHistory });
}

function parseLabExpectation(value) {
  const normalized = normalizeLabel(value);
  if (normalized.startsWith("offensive")) {
    return "offensive";
  }
  if (normalized.startsWith("safe")) {
    return "safe";
  }
  return "unknown";
}

function getLabCaseMaskedState(element) {
  if (!(element instanceof Element)) {
    return false;
  }

  if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) {
    const candidate = buildEditableValueCandidate(element);
    return Boolean(candidate?.state?.isMasked);
  }

  return Boolean(
    element.querySelector(
      ".shieldtext-inline-mask, .shieldtext-inline-hide, .shieldtext-editable-mask, .shieldtext-editable-hide, .shieldtext-editable-bar-mask"
    )
  );
}

function collectLabCaseEntries() {
  const entries = [...document.querySelectorAll("[data-chungmaru-case-id][data-chungmaru-expectation]")]
    .map((element) => {
      const caseElement = element instanceof Element ? element : null;
      if (!caseElement) {
        return null;
      }

      const sampleText =
        caseElement instanceof HTMLInputElement || caseElement instanceof HTMLTextAreaElement
          ? String(caseElement.value || "")
          : String(
              caseElement.dataset.chungmaruSampleText ||
                caseElement.textContent ||
                ""
            ).trim();
      const rect = caseElement.getBoundingClientRect();

      return {
        element: caseElement,
        caseId: String(caseElement.dataset.chungmaruCaseId || ""),
        caseKind: String(caseElement.dataset.chungmaruCaseKind || "card"),
        expectation: String(caseElement.dataset.chungmaruExpectation || ""),
        expectationKind: parseLabExpectation(caseElement.dataset.chungmaruExpectation || ""),
        sampleText,
        visible: rect.bottom > 0 && rect.top < window.innerHeight
      };
    })
    .filter((entry) => entry && entry.sampleText);

  entries.sort((left, right) => {
    if (left.visible !== right.visible) {
      return left.visible ? -1 : 1;
    }
    if (left.caseKind !== right.caseKind) {
      return left.caseKind === "editable" ? -1 : 1;
    }
    return left.caseId.localeCompare(right.caseId);
  });

  return entries.slice(0, MAX_SELF_TEST_CASES);
}

function sleep(ms) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

async function waitForRuntimeToSettle(timeoutMs = 4000) {
  const startedAt = performance.now();
  while (isPipelineRunning || isReconcileRunning) {
    if ((performance.now() - startedAt) >= timeoutMs) {
      break;
    }
    await sleep(24);
  }
}

async function runFilterLabSelfTest() {
  if (!isChungmaruFilterLabPage()) {
    return {
      ok: false,
      errorCode: "UNSUPPORTED_SELF_TEST_PAGE",
      reason: "청마루 필터 테스트 랩 페이지에서만 self-test를 실행할 수 있습니다."
    };
  }

  setLabSelfTestOutput("self-test 실행 중...");
  const startedAt = performance.now();

  await waitForRuntimeToSettle(1500);
  await executePipeline("manual");
  await waitForRuntimeToSettle(2500);

  if (RECONCILE_QUEUE.size > 0 && !isReconcileRunning) {
    await flushReconcileQueue();
    await waitForRuntimeToSettle(2500);
  }

  const labCases = collectLabCaseEntries();
  if (labCases.length === 0) {
    const failure = {
      ok: false,
      errorCode: "SELF_TEST_CASES_NOT_FOUND",
      reason: "self-test 대상 케이스를 찾지 못했습니다."
    };
    setLabSelfTestOutput(failure);
    const storedFailure = { ...failure, timestamp: Date.now(), url: location.href };
    await safeStorageLocalSet({ lastSelfTest: storedFailure });
    await appendSelfTestHistory(storedFailure);
    return failure;
  }

  const backendResponse = await safeRuntimeSendMessage({
    type: "ANALYZE_TEXT_BATCH",
    texts: labCases.map((entry) => entry.sampleText),
    sensitivity: normalizeSensitivity(cachedSettings?.sensitivity ?? DEFAULT_SETTINGS.sensitivity),
    requestTimeoutMsOverride: RECONCILE_ANALYZE_TIMEOUT_MS,
    analysisMode: "self-test"
  });

  const backendResults = Array.isArray(backendResponse?.results) ? backendResponse.results : [];
  const runtimeState = await safeStorageLocalGet(["lastStats", "lastPipelineError", "lastDecision"]);

  const cases = labCases.map((entry, index) => {
    const backendResult = backendResults[index] || null;
    const backendSpans = normalizeEvidenceSpans(
      backendResult?.evidence_spans,
      entry.sampleText
    );
    const backendOffensive = Boolean(backendResult?.is_offensive);
    const extensionMasked = getLabCaseMaskedState(entry.element);
    const expectedOffensive = entry.expectationKind === "offensive";
    const expectedSafe = entry.expectationKind === "safe";
    const backendMatchesExpectation =
      entry.expectationKind === "unknown"
        ? null
        : backendOffensive === expectedOffensive;
    const extensionMatchesExpectation =
      entry.expectationKind === "unknown"
        ? null
        : extensionMasked === expectedOffensive;

    return {
      caseId: entry.caseId,
      caseKind: entry.caseKind,
      expectation: entry.expectation,
      expectationKind: entry.expectationKind,
      sampleText: truncateDiagnosticText(entry.sampleText, 180),
      visible: entry.visible,
      extensionMasked,
      backendOffensive,
      backendSpanCount: backendSpans.length,
      backendErrorCode: backendResponse?.ok ? null : String(backendResponse?.errorCode || backendResponse?.reason || ""),
      backendMatchesExpectation,
      extensionMatchesExpectation,
      extensionMatchesBackend: backendResponse?.ok ? extensionMasked === backendOffensive : null,
      pass:
        expectedSafe || expectedOffensive
          ? Boolean(backendMatchesExpectation && extensionMatchesExpectation)
          : null
    };
  });

  const summary = {
    ok: cases.every((entry) => entry.pass !== false),
    totalCases: cases.length,
    visibleCases: cases.filter((entry) => entry.visible).length,
    failedCases: cases.filter((entry) => entry.pass === false).length,
    backendMismatchCount: cases.filter((entry) => entry.backendMatchesExpectation === false).length,
    extensionMismatchCount: cases.filter((entry) => entry.extensionMatchesExpectation === false).length,
    extensionBackendMismatchCount: cases.filter((entry) => entry.extensionMatchesBackend === false).length
  };

  const report = {
    ok: summary.ok,
    timestamp: Date.now(),
    url: location.href,
    durationMs: Math.round(performance.now() - startedAt),
    summary,
    backend: {
      ok: Boolean(backendResponse?.ok),
      errorCode: backendResponse?.ok ? null : String(backendResponse?.errorCode || backendResponse?.reason || ""),
      apiBaseUrl: String(backendResponse?.apiBaseUrl || ""),
      durationMs: Number(backendResponse?.durationMs || 0),
      requestCount: Number(backendResponse?.requestCount || 0),
      splitRetryCount: Number(backendResponse?.splitRetryCount || 0),
      chunkSize: Number(backendResponse?.chunkSize || 0)
    },
    diagnostics: {
      lastStats: runtimeState.lastStats || null,
      lastPipelineError: runtimeState.lastPipelineError || null,
      blockedNodeCount: Number(runtimeState?.lastStats?.blockedNodeCount || 0),
      maskedSpanCount: Number(runtimeState?.lastStats?.maskedSpanCount || 0),
      decisionSource: String(runtimeState?.lastStats?.lastDecisionSource || "-")
    },
    cases
  };

  setLabSelfTestOutput(report);
  await safeStorageLocalSet({ lastSelfTest: report });
  await appendSelfTestHistory(report);
  return report;
}

function initializeLabSelfTestListeners() {
  if (!isChungmaruFilterLabPage()) {
    return;
  }

  document.addEventListener(
    "click",
    (event) => {
      const target = event.target instanceof Element
        ? event.target.closest("#run-self-test")
        : null;
      if (!target) {
        return;
      }

      event.preventDefault();
      runFilterLabSelfTest().catch((error) => {
        const failure = {
          ok: false,
          errorCode: String(error?.errorCode || "SELF_TEST_FAILED"),
          reason: String(error?.message || error || "SELF_TEST_FAILED")
        };
        setLabSelfTestOutput(failure);
        const storedFailure = {
          ...failure,
          timestamp: Date.now(),
          url: location.href
        };
        safeStorageLocalSet({
          lastSelfTest: storedFailure
        }).catch(() => {});
        appendSelfTestHistory(storedFailure).catch(() => {});
      });
    },
    true
  );
}
