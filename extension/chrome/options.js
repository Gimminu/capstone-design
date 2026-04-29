const DEFAULT_SETTINGS = {
  customBlockWords: "",
  customAllowWords: "",
  showReason: true,
  backendApiBaseUrl: "http://127.0.0.1:8000",
  requestTimeoutMs: 10000
};

const els = {
  blockWords: document.getElementById("blockWords"),
  allowWords: document.getElementById("allowWords"),
  showReasonToggle: document.getElementById("showReasonToggle"),
  backendApiBaseUrl: document.getElementById("backendApiBaseUrl"),
  requestTimeoutMs: document.getElementById("requestTimeoutMs"),
  checkConnectionButton: document.getElementById("checkConnectionButton"),
  connectionStatusText: document.getElementById("connectionStatusText"),
  runNowButton: document.getElementById("runNowButton"),
  saveOptionsButton: document.getElementById("saveOptionsButton"),
  refreshJsonButton: document.getElementById("refreshJsonButton"),
  runSelfTestButton: document.getElementById("runSelfTestButton"),
  payloadPreview: document.getElementById("payloadPreview"),
  decisionPreview: document.getElementById("decisionPreview"),
  selfTestPreview: document.getElementById("selfTestPreview"),
  selfTestHistoryPreview: document.getElementById("selfTestHistoryPreview"),
  optionsStatus: document.getElementById("optionsStatus"),
  statBlocked: document.getElementById("statBlocked"),
  statFalsePositive: document.getElementById("statFalsePositive"),
  statLatency: document.getElementById("statLatency"),
  statAnalyzed: document.getElementById("statAnalyzed"),
  diagFirstMask: document.getElementById("diagFirstMask"),
  diagHotPath: document.getElementById("diagHotPath"),
  diagHotPathState: document.getElementById("diagHotPathState"),
  diagHotPathError: document.getElementById("diagHotPathError"),
  diagWorkerInit: document.getElementById("diagWorkerInit"),
  diagBackendReconcile: document.getElementById("diagBackendReconcile"),
  diagMaskedSpans: document.getElementById("diagMaskedSpans"),
  diagWorkerCacheHit: document.getElementById("diagWorkerCacheHit"),
  diagBackendCacheHit: document.getElementById("diagBackendCacheHit"),
  diagVisibleBatch: document.getElementById("diagVisibleBatch"),
  diagReconcileQueue: document.getElementById("diagReconcileQueue"),
  diagDecisionSource: document.getElementById("diagDecisionSource")
};

let currentSettings = null;
let isRunningPipeline = false;
let isRunningSelfTest = false;

function mergeSettings(stored) {
  return {
    ...DEFAULT_SETTINGS,
    ...(stored || {})
  };
}

function sanitizeApiBaseUrl(value) {
  const normalized = String(value || DEFAULT_SETTINGS.backendApiBaseUrl).trim();
  if (!normalized) return DEFAULT_SETTINGS.backendApiBaseUrl;
  return normalized.replace(/\/+$/, "");
}

function normalizeRequestTimeoutMs(value) {
  const numeric = Number(value);
  if (Number.isNaN(numeric)) return DEFAULT_SETTINGS.requestTimeoutMs;
  return Math.max(1000, Math.min(30000, Math.round(numeric)));
}

function renderStatus(message) {
  els.optionsStatus.textContent = message;
  if (!message) return;

  window.setTimeout(() => {
    if (els.optionsStatus.textContent === message) {
      els.optionsStatus.textContent = "";
    }
  }, 2200);
}

function mapRunFailureReason(reason, errorCode) {
  const code = String(errorCode || "");
  const value = String(reason || "");
  if (code === "UNSUPPORTED_TAB" || value.includes("UNSUPPORTED_TAB")) {
    return "지원되지 않는 탭입니다 (chrome://, 확장 페이지 등)";
  }
  if (code === "ACTIVE_TAB_NOT_FOUND" || value.includes("ACTIVE_TAB_NOT_FOUND")) {
    return "현재 활성 탭을 찾지 못했습니다";
  }
  if (value.includes("Cannot access contents of url")) {
    return "이 페이지는 크롬 정책상 접근할 수 없습니다";
  }
  if (code === "HTTP_503" || value.includes("HTTP_503")) {
    return "백엔드 모델이 아직 준비되지 않았습니다";
  }
  if (code === "NETWORK_UNREACHABLE") {
    return "백엔드 서버에 연결할 수 없습니다";
  }
  if (code === "TIMEOUT") {
    return "백엔드 응답 시간이 초과되었습니다";
  }
  if (code === "ABORTED") {
    return "백엔드 요청이 취소되었습니다";
  }
  return code || value || "unknown";
}

function setRunNowBusy(isBusy) {
  if (!els.runNowButton) return;
  els.runNowButton.disabled = isBusy;
  els.runNowButton.textContent = isBusy ? "분석 중..." : "현재 탭 즉시 분석";
}

function renderStats(stats) {
  els.statBlocked.textContent = String(Number(stats?.blockedCount || 0));
  els.statFalsePositive.textContent = String(Number(stats?.falsePositiveCount || 0));
  els.statLatency.textContent = `${Number(stats?.averageLatencyMs || 0)}ms`;
  els.statAnalyzed.textContent = String(Number(stats?.totalAnalyzedCount || 0));
}

function formatLatency(value) {
  const numeric = Number(value || 0);
  if (!numeric) return "-";
  return `${numeric}ms`;
}

function renderDiagnostics(lastStats) {
  els.diagFirstMask.textContent = formatLatency(lastStats?.firstMaskLatencyMs);
  els.diagHotPath.textContent = formatLatency(
    lastStats?.foregroundBackendLatencyMs ?? lastStats?.hotPathLatencyMs
  );
  els.diagHotPathState.textContent = String(lastStats?.hotPathStatus || "idle");
  els.diagHotPathError.textContent = String(lastStats?.hotPathErrorCode || "-");
  els.diagWorkerInit.textContent = String(lastStats?.foregroundBackendSource || "-");
  els.diagBackendReconcile.textContent = formatLatency(lastStats?.backendReconcileLatencyMs);
  els.diagMaskedSpans.textContent = String(Number(lastStats?.maskedSpanCount || 0));
  els.diagWorkerCacheHit.textContent = String(Number(lastStats?.workerCacheHitCount || 0));
  els.diagBackendCacheHit.textContent = String(Number(lastStats?.backendCacheHitCount || 0));
  els.diagVisibleBatch.textContent = String(Number(lastStats?.visibleContainerBatchSize || 0));
  els.diagReconcileQueue.textContent = String(Number(lastStats?.reconcileQueueDepth || 0));
  els.diagDecisionSource.textContent = String(lastStats?.lastDecisionSource || "-");
}

function stringifyPreview(value) {
  if (!value) return "(데이터 없음)";

  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return "(JSON 직렬화 실패)";
  }
}

function summarizeSelfTestResult(value) {
  if (!value) {
    return "(self-test 결과 없음)";
  }

  const summary = value?.summary || {};
  return stringifyPreview({
    ok: Boolean(value?.ok),
    timestamp: value?.timestamp || null,
    url: value?.url || null,
    durationMs: Number(value?.durationMs || 0),
    summary: {
      totalCases: Number(summary.totalCases || 0),
      visibleCases: Number(summary.visibleCases || 0),
      failedCases: Number(summary.failedCases || 0),
      backendMismatchCount: Number(summary.backendMismatchCount || 0),
      extensionMismatchCount: Number(summary.extensionMismatchCount || 0),
      extensionBackendMismatchCount: Number(summary.extensionBackendMismatchCount || 0)
    },
    backend: value?.backend || null,
    cases: Array.isArray(value?.cases) ? value.cases.filter((entry) => entry.pass === false) : []
  });
}

function summarizeSelfTestHistory(items) {
  if (!Array.isArray(items) || items.length === 0) {
    return "(self-test 이력 없음)";
  }

  const repeatedFailures = new Map();
  for (const item of items) {
    for (const entry of Array.isArray(item?.cases) ? item.cases : []) {
      if (entry?.pass !== false) {
        continue;
      }

      const key = `${entry.caseId || "unknown"}::${entry.expectationKind || "unknown"}`;
      const current = repeatedFailures.get(key) || {
        caseId: entry.caseId || "unknown",
        expectationKind: entry.expectationKind || "unknown",
        sampleText: entry.sampleText || "",
        count: 0,
        backendMismatchCount: 0,
        extensionMismatchCount: 0
      };
      current.count += 1;
      if (entry.backendMatchesExpectation === false) {
        current.backendMismatchCount += 1;
      }
      if (entry.extensionMatchesExpectation === false) {
        current.extensionMismatchCount += 1;
      }
      repeatedFailures.set(key, current);
    }
  }

  return stringifyPreview({
    recentRuns: items.slice(0, 10).map((item) => ({
      timestamp: item?.timestamp || null,
      ok: Boolean(item?.ok),
      url: item?.url || null,
      durationMs: Number(item?.durationMs || 0),
      totalCases: Number(item?.summary?.totalCases || 0),
      failedCases: Number(item?.summary?.failedCases || 0),
      backendMismatchCount: Number(item?.summary?.backendMismatchCount || 0),
      extensionMismatchCount: Number(item?.summary?.extensionMismatchCount || 0)
    })),
    repeatedFailures: [...repeatedFailures.values()]
      .sort((left, right) => right.count - left.count)
      .slice(0, 12)
  });
}

function formatConnectionStatus(result) {
  if (!result) return "아직 연결 확인 전";

  if (!result.ok) {
    return `degraded · ${result.errorCode || result.reason || "unknown"}`;
  }

  if (result.model_ready === false) {
    return `degraded · MODEL_NOT_READY (${result.apiBaseUrl})`;
  }

  return `ready · ${result.apiBaseUrl}`;
}

async function refreshConnectionState() {
  const result = await chrome.runtime.sendMessage({ type: "CHECK_API_HEALTH" });
  els.connectionStatusText.textContent = formatConnectionStatus(result);
  return result;
}

async function runPipelineNowFromOptions() {
  if (isRunningPipeline) return;

  isRunningPipeline = true;
  setRunNowBusy(true);

  try {
    const response = await chrome.runtime.sendMessage({ type: "RUN_PIPELINE_ON_ACTIVE_TAB" });
    if (!response?.ok) {
      renderStatus(`분석 실패: ${mapRunFailureReason(response?.reason, response?.errorCode)}`);
      return;
    }

    await loadRuntimeState();
    renderStatus("현재 탭 분석 완료");
  } finally {
    isRunningPipeline = false;
    setRunNowBusy(false);
  }
}

async function runSelfTestFromOptions() {
  if (isRunningSelfTest) return;

  isRunningSelfTest = true;
  if (els.runSelfTestButton) {
    els.runSelfTestButton.disabled = true;
    els.runSelfTestButton.textContent = "실행 중...";
  }

  try {
    const response = await chrome.runtime.sendMessage({ type: "RUN_SELF_TEST_ON_ACTIVE_TAB" });
    if (!response?.ok) {
      renderStatus(`self-test 실패: ${mapRunFailureReason(response?.reason, response?.errorCode)}`);
      return;
    }

    await loadRuntimeState();
    renderStatus("self-test 완료");
  } finally {
    isRunningSelfTest = false;
    if (els.runSelfTestButton) {
      els.runSelfTestButton.disabled = false;
      els.runSelfTestButton.textContent = "self-test 실행";
    }
  }
}

async function loadRuntimeState() {
  const state = await chrome.runtime.sendMessage({ type: "GET_LAST_PIPELINE_STATE" });

  if (!state?.ok) {
    els.payloadPreview.value = "(상태 조회 실패)";
    els.decisionPreview.value = "(상태 조회 실패)";
    if (els.selfTestPreview) {
      els.selfTestPreview.value = "(상태 조회 실패)";
    }
    if (els.selfTestHistoryPreview) {
      els.selfTestHistoryPreview.value = "(상태 조회 실패)";
    }
    renderStats(null);
    return;
  }

  els.payloadPreview.value = stringifyPreview(state.lastPayload);
  els.decisionPreview.value = stringifyPreview(
    state.lastPipelineError
      ? {
          lastDecision: state.lastDecision || null,
          lastPipelineError: state.lastPipelineError,
          lastForegroundDiagnostics: state?.lastStats?.lastForegroundDiagnostics || null,
          lastReconcileDiagnostics: state?.lastStats?.lastReconcileDiagnostics || null
        }
      : {
          lastDecision: state.lastDecision || null,
          lastForegroundDiagnostics: state?.lastStats?.lastForegroundDiagnostics || null,
          lastReconcileDiagnostics: state?.lastStats?.lastReconcileDiagnostics || null
        }
  );
  if (els.selfTestPreview) {
    els.selfTestPreview.value = summarizeSelfTestResult(state.lastSelfTest);
  }
  if (els.selfTestHistoryPreview) {
    els.selfTestHistoryPreview.value = summarizeSelfTestHistory(state.lastSelfTestHistory);
  }
  renderStats(state.sessionStats);
  renderDiagnostics(state.lastStats);
}

function readSettingsFromForm() {
  return {
    ...currentSettings,
    customBlockWords: els.blockWords.value.trim(),
    customAllowWords: els.allowWords.value.trim(),
    showReason: els.showReasonToggle.checked,
    backendApiBaseUrl: sanitizeApiBaseUrl(els.backendApiBaseUrl.value),
    requestTimeoutMs: normalizeRequestTimeoutMs(els.requestTimeoutMs.value)
  };
}

function renderSettingsToForm(settings) {
  els.blockWords.value = settings.customBlockWords;
  els.allowWords.value = settings.customAllowWords;
  els.showReasonToggle.checked = settings.showReason;
  els.backendApiBaseUrl.value = settings.backendApiBaseUrl;
  els.requestTimeoutMs.value = String(settings.requestTimeoutMs);
}

async function initialize() {
  const { settings } = await chrome.storage.sync.get("settings");
  currentSettings = mergeSettings(settings || {});

  renderSettingsToForm(currentSettings);
  await Promise.all([loadRuntimeState(), refreshConnectionState()]);

  els.saveOptionsButton.addEventListener("click", async () => {
    currentSettings = readSettingsFromForm();
    await chrome.storage.sync.set({ settings: currentSettings });
    await refreshConnectionState();
    renderStatus("옵션 저장 완료");
    await runPipelineNowFromOptions();
  });

  els.refreshJsonButton.addEventListener("click", async () => {
    await loadRuntimeState();
    renderStatus("최근 JSON 새로고침 완료");
  });

  els.runNowButton.addEventListener("click", async () => {
    await runPipelineNowFromOptions();
  });

  if (els.runSelfTestButton) {
    els.runSelfTestButton.addEventListener("click", async () => {
      await runSelfTestFromOptions();
    });
  }

  els.checkConnectionButton.addEventListener("click", async () => {
    const result = await refreshConnectionState();
    renderStatus(result?.ok ? "백엔드 연결 확인 완료" : "백엔드 연결 실패");
  });

  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName === "local" && (changes.lastPayload || changes.lastDecision || changes.lastRunAt || changes.sessionStats || changes.lastPipelineError || changes.lastSelfTest || changes.lastSelfTestHistory)) {
      loadRuntimeState().catch(() => {});
      return;
    }

    if (areaName === "sync" && changes.settings?.newValue) {
      currentSettings = mergeSettings(changes.settings.newValue);
      renderSettingsToForm(currentSettings);
      refreshConnectionState().catch(() => {});
    }
  });
}

initialize().catch((error) => {
  console.error(error);
  renderStatus("옵션 로드 실패");
});
