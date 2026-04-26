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

function getLabCaseRenderState(element, sampleText) {
  if (!(element instanceof Element)) {
    return {
      editable: false,
      editableTagName: "",
      maskMode: "",
      maskElementCount: 0,
      editableTitle: "",
      editableConcealsText: false,
      suspiciousEditableBar: false,
      suspiciousNativeTextareaMask: false,
      editableHardConcealed: false,
      suspiciousHardConcealmentMissing: false,
      overlayDriftPx: 0,
      suspiciousOverlayDrift: false,
      suspiciousMaskTextVisible: false
    };
  }

  if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) {
    const candidate = buildEditableValueCandidate(element);
    const state = candidate?.state || null;
    const text = String(sampleText || candidate?.text || "");
    const compactLength = text.replace(/\s+/g, "").length;
    const maskMode = state?.nativeMaskApplied
      ? "native-mask"
      : String(state?.overlayMode || "");
    const inlineColor = String(element.style.getPropertyValue("color") || "").trim();
    const inlineFill = String(element.style.getPropertyValue("-webkit-text-fill-color") || "").trim();
    const inlineFilter = String(element.style.getPropertyValue("filter") || "").trim();
    const inlineOpacity = String(element.style.getPropertyValue("opacity") || "").trim();
    const requiresHardConcealment =
      typeof shouldUseHardEditableConcealment === "function" &&
      shouldUseHardEditableConcealment(element);
    const editableHardConcealed =
      /opacity\s*\(\s*0\s*\)/i.test(inlineFilter) ||
      Number(inlineOpacity || 1) === 0;
    const editableConcealsText =
      inlineColor === "transparent" ||
      inlineFill === "transparent" ||
      editableHardConcealed ||
      Boolean(state?.nativeMaskApplied);
    const overlayRect = state?.overlayRoot?.isConnected
      ? state.overlayRoot.getBoundingClientRect()
      : null;
    const elementRect = element.getBoundingClientRect();
    const overlayDriftPx = overlayRect
      ? Math.max(
          Math.abs(overlayRect.left - elementRect.left),
          Math.abs(overlayRect.top - elementRect.top),
          Math.abs(overlayRect.width - elementRect.width),
          Math.abs(overlayRect.height - elementRect.height)
        )
      : 0;
    const suspiciousMaskTextVisible = hasVisibleMaskText(state?.overlayRoot);

    return {
      editable: true,
      editableTagName: element.tagName,
      maskMode,
      maskElementCount: state?.overlayRoot?.isConnected
        ? state.overlayRoot.querySelectorAll(
            ".shieldtext-editable-mask, .shieldtext-editable-hide, .shieldtext-editable-bar-mask"
          ).length
        : 0,
      editableTitle: String(element.getAttribute("title") || ""),
      editableConcealsText,
      suspiciousEditableBar: maskMode === "single-line-bars" && compactLength <= 8,
      suspiciousNativeTextareaMask:
        element instanceof HTMLTextAreaElement && maskMode === "native-mask",
      editableHardConcealed,
      requiresHardConcealment,
      sourceFilter: inlineFilter,
      sourceOpacity: inlineOpacity,
      suspiciousHardConcealmentMissing:
        Boolean(state?.isMasked) &&
        requiresHardConcealment &&
        maskMode === "full-overlay" &&
        !editableHardConcealed,
      overlayDriftPx: Math.round(overlayDriftPx),
      suspiciousOverlayDrift: Boolean(overlayRect && overlayDriftPx > 4),
      suspiciousMaskTextVisible
    };
  }

  return {
    editable: false,
    maskMode: "",
    maskElementCount: element.querySelectorAll(
      ".shieldtext-inline-mask, .shieldtext-inline-hide, .shieldtext-editable-mask, .shieldtext-editable-hide, .shieldtext-editable-bar-mask"
    ).length,
    editableTagName: "",
    editableTitle: "",
    editableConcealsText: false,
    suspiciousEditableBar: false,
    suspiciousNativeTextareaMask: false,
    editableHardConcealed: false,
    requiresHardConcealment: false,
    sourceFilter: "",
    suspiciousHardConcealmentMissing: false,
    overlayDriftPx: 0,
    suspiciousOverlayDrift: false,
    suspiciousMaskTextVisible: hasVisibleMaskText(element)
  };
}

function getBackendMaskExpectation(backendResponse, backendOffensive, backendSpans) {
  if (!backendResponse?.ok) {
    return null;
  }

  // The extension renderer must never infer a mask from the boolean alone.
  // A positive backend decision still needs at least one exact, valid span.
  return Boolean(backendOffensive && Array.isArray(backendSpans) && backendSpans.length > 0);
}

function hasVisibleMaskText(root) {
  if (!(root instanceof Element)) {
    return false;
  }

  return [...root.querySelectorAll(".shieldtext-hidden-mask-text")].some((element) => {
    const style = window.getComputedStyle(element);
    const color = String(style.color || "").replace(/\s+/g, "").toLowerCase();
    const fill = String(style.webkitTextFillColor || "").replace(/\s+/g, "").toLowerCase();
    const hiddenByVisibility = style.visibility === "hidden";
    const hiddenByOpacity = Number(style.opacity || 1) <= 0.01;
    const hiddenByTransparentPaint =
      color === "transparent" ||
      fill === "transparent" ||
      color === "rgba(0,0,0,0)" ||
      fill === "rgba(0,0,0,0)";
    return !(hiddenByVisibility || hiddenByOpacity || hiddenByTransparentPaint);
  });
}

function isLabRenderStateHealthy(renderState, extensionMasked) {
  if (!renderState || typeof renderState !== "object") {
    return !extensionMasked;
  }

  if (!extensionMasked) {
    return (
      !renderState.editableConcealsText &&
      String(renderState.editableTitle || "") === "" &&
      Number(renderState.maskElementCount || 0) === 0 &&
      !renderState.suspiciousEditableBar &&
      !renderState.suspiciousNativeTextareaMask &&
      !renderState.suspiciousOverlayDrift &&
      !renderState.suspiciousMaskTextVisible
    );
  }

  if (renderState.editable) {
    return (
      String(renderState.editableTitle || "") === "" &&
      !renderState.suspiciousEditableBar &&
      !renderState.suspiciousNativeTextareaMask &&
      !renderState.suspiciousHardConcealmentMissing &&
      !renderState.suspiciousOverlayDrift &&
      !renderState.suspiciousMaskTextVisible &&
      (renderState.maskMode === "native-mask" || Number(renderState.maskElementCount || 0) > 0)
    );
  }

  return Number(renderState.maskElementCount || 0) > 0 && !renderState.suspiciousMaskTextVisible;
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
    const backendExpectedMasked = getBackendMaskExpectation(
      backendResponse,
      backendOffensive,
      backendSpans
    );
    const extensionMasked = getLabCaseMaskedState(entry.element);
    const renderState = getLabCaseRenderState(entry.element, entry.sampleText);
    const renderHealthy = isLabRenderStateHealthy(renderState, extensionMasked);
    const expectedOffensive = entry.expectationKind === "offensive";
    const expectedSafe = entry.expectationKind === "safe";
    const backendMatchesExpectation =
      entry.expectationKind === "unknown"
        ? null
        : backendOffensive === expectedOffensive;
    const extensionMatchesExpectation = (() => {
      if (entry.expectationKind === "unknown") {
        return null;
      }

      if (expectedSafe) {
        return !extensionMasked && renderHealthy;
      }

      if (!extensionMasked) {
        return false;
      }

      return renderHealthy;
    })();
    const extensionMatchesBackend =
      backendExpectedMasked === null
        ? null
        : extensionMasked === backendExpectedMasked && renderHealthy;

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
      backendExpectedMasked,
      renderState,
      renderHealthy,
      backendErrorCode: backendResponse?.ok ? null : String(backendResponse?.errorCode || backendResponse?.reason || ""),
      backendMatchesExpectation,
      extensionMatchesExpectation,
      extensionMatchesBackend,
      pass:
        expectedSafe || expectedOffensive
          ? Boolean(backendMatchesExpectation && extensionMatchesExpectation && extensionMatchesBackend !== false)
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
