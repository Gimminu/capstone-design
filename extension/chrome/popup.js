const DEFAULT_SETTINGS = {
  enabled: true,
  sensitivity: 60,
  categories: {
    abuse: true,
    hate: true,
    insult: true,
    spam: true
  },
  interventionMode: "mask",
  customBlockWords: "",
  customAllowWords: "",
  blockedDomains: "",
  warnDomains: "",
  showReason: true
};

const sampleTokens = [
  { word: "열받네", category: "insult" },
  { word: "스팸", category: "spam" },
  { word: "너는 왜", category: "abuse" }
];

const els = {
  enabledToggle: document.getElementById("enabledToggle"),
  sensitivityRange: document.getElementById("sensitivityRange"),
  sensitivityLabel: document.getElementById("sensitivityLabel"),
  catAbuse: document.getElementById("catAbuse"),
  catHate: document.getElementById("catHate"),
  catInsult: document.getElementById("catInsult"),
  catSpam: document.getElementById("catSpam"),
  previewLine: document.getElementById("previewLine"),
  statusMessage: document.getElementById("statusMessage"),
  applyNowButton: document.getElementById("applyNowButton"),
  openOptionsButton: document.getElementById("openOptionsButton"),
  runtimeLastRun: document.getElementById("runtimeLastRun"),
  runtimeAnalyzed: document.getElementById("runtimeAnalyzed"),
  runtimeBlocked: document.getElementById("runtimeBlocked"),
  runtimeFirstMask: document.getElementById("runtimeFirstMask"),
  runtimeHotPath: document.getElementById("runtimeHotPath"),
  runtimeHotPathState: document.getElementById("runtimeHotPathState"),
  runtimeHotPathError: document.getElementById("runtimeHotPathError"),
  runtimeWorkerInit: document.getElementById("runtimeWorkerInit"),
  runtimeBackendLatency: document.getElementById("runtimeBackendLatency"),
  runtimeMaskedSpans: document.getElementById("runtimeMaskedSpans"),
  runtimeQueueDepth: document.getElementById("runtimeQueueDepth"),
  runtimeDecisionSource: document.getElementById("runtimeDecisionSource"),
  runtimeHostname: document.getElementById("runtimeHostname"),
  runtimeBackend: document.getElementById("runtimeBackend"),
  runtimeBackendState: document.getElementById("runtimeBackendState")
};

let isRunningPipeline = false;

function getModeInputs() {
  return [...document.querySelectorAll('input[name="mode"]')];
}

function mergeSettings(stored) {
  return {
    ...DEFAULT_SETTINGS,
    ...(stored || {}),
    categories: {
      ...DEFAULT_SETTINGS.categories,
      ...(stored?.categories || {})
    }
  };
}

async function loadSettings() {
  const { settings } = await chrome.storage.sync.get("settings");
  return mergeSettings(settings || {});
}

async function saveSettings(settings) {
  await chrome.storage.sync.set({ settings });
}

function renderStatus(message) {
  els.statusMessage.textContent = message;
  if (!message) return;

  window.setTimeout(() => {
    if (els.statusMessage.textContent === message) {
      els.statusMessage.textContent = "";
    }
  }, 2000);
}

function setApplyButtonBusy(isBusy) {
  els.applyNowButton.disabled = isBusy;
  els.applyNowButton.textContent = isBusy ? "분석 중..." : "현재 탭 즉시 분석";
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

function maskWord(word, mode) {
  if (mode === "hide") return "[숨김]";
  return "█".repeat(Math.max(2, word.length));
}

function applyPreview(settings) {
  const base = "진짜 열받네 이건 완전 스팸이야 너는 왜 그렇게 말해";

  if (!settings.enabled) {
    els.previewLine.textContent = base;
    return;
  }

  let next = base;
  for (const token of sampleTokens) {
    if (!settings.categories[token.category]) continue;
    next = next.replaceAll(token.word, maskWord(token.word, settings.interventionMode));
  }

  els.previewLine.textContent = next;
}

function formatTimestamp(timestamp) {
  const numberTime = Number(timestamp || 0);
  if (!numberTime) return "-";

  return new Intl.DateTimeFormat("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(new Date(numberTime));
}

function safeParseHostname(url) {
  try {
    return new URL(url).hostname || "-";
  } catch {
    return "-";
  }
}

function formatLatency(value) {
  const numeric = Number(value || 0);
  if (!numeric) return "-";
  return `${numeric}ms`;
}

async function getActiveTabHostname() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  const tab = tabs?.[0];
  if (!tab?.url) return "-";
  return safeParseHostname(tab.url);
}

async function notifyActiveTabSettingsSnapshot(settings) {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  const tab = tabs?.[0];
  if (!tab?.id) return { ok: false, reason: "ACTIVE_TAB_NOT_FOUND" };

  try {
    return await chrome.tabs.sendMessage(tab.id, {
      type: "APPLY_SETTINGS_SNAPSHOT",
      settings
    });
  } catch (error) {
    const message = String(error?.message || error || "");
    if (message.includes("Receiving end does not exist")) {
      return { ok: false, reason: "CONTENT_SCRIPT_NOT_READY" };
    }
    return { ok: false, reason: message || "SETTINGS_SNAPSHOT_FAILED" };
  }
}

async function persistAndApplySettings(settings, statusMessage) {
  await saveSettings(settings);
  applyPreview(settings);
  if (statusMessage) {
    renderStatus(statusMessage);
  }

  const snapshotResult = await notifyActiveTabSettingsSnapshot(settings);
  if (!snapshotResult?.ok) {
    await runPipelineNow();
    return {
      ok: true,
      appliedBy: "pipeline-fallback",
      reason: snapshotResult?.reason || "SNAPSHOT_FAILED"
    };
  }

  await refreshRuntimeState();
  return {
    ok: true,
    appliedBy: "settings-snapshot"
  };
}

function formatBackendStatus(health, state) {
  if (health?.backendStatus === "ready" && health?.ok) return "ready";
  if (health?.ok && health.model_ready === false) return "degraded (MODEL_NOT_READY)";
  if (!health?.ok && (health?.errorCode || health?.reason)) {
    return `degraded (${health?.errorCode || health?.reason})`;
  }
  if (state?.lastPipelineError?.errorCode || state?.lastPipelineError?.reason) {
    return `degraded (${state?.lastPipelineError?.errorCode || state?.lastPipelineError?.reason})`;
  }
  return "-";
}

function renderRuntimeState(state, fallbackHostname, health) {
  const analyzedNodeCount = Number(state?.lastStats?.analyzedNodeCount ?? 0);
  const blockedNodeCount = Number(state?.lastStats?.blockedNodeCount ?? 0);
  const firstMaskLatencyMs = Number(state?.lastStats?.firstMaskLatencyMs ?? 0);
  const hotPathLatencyMs = Number(
    state?.lastStats?.foregroundBackendLatencyMs ?? state?.lastStats?.hotPathLatencyMs ?? 0
  );
  const hotPathStatus = String(state?.lastStats?.hotPathStatus || "idle");
  const hotPathErrorCode = String(state?.lastStats?.hotPathErrorCode || "");
  const foregroundBackendSource = String(state?.lastStats?.foregroundBackendSource || "-");
  const backendReconcileLatencyMs = Number(state?.lastStats?.backendReconcileLatencyMs ?? 0);
  const maskedSpanCount = Number(state?.lastStats?.maskedSpanCount ?? 0);
  const reconcileQueueDepth = Number(state?.lastStats?.reconcileQueueDepth ?? 0);
  const decisionSource = String(state?.lastStats?.lastDecisionSource || "-");
  const hostname = state?.lastStats?.hostname || fallbackHostname || "-";
  const backendEndpoint = health?.apiBaseUrl || state?.lastStats?.backendEndpoint || "-";

  els.runtimeLastRun.textContent = formatTimestamp(state?.lastRunAt);
  els.runtimeAnalyzed.textContent = String(analyzedNodeCount);
  els.runtimeBlocked.textContent = String(blockedNodeCount);
  els.runtimeFirstMask.textContent = formatLatency(firstMaskLatencyMs);
  els.runtimeHotPath.textContent = formatLatency(hotPathLatencyMs);
  els.runtimeHotPathState.textContent = hotPathStatus;
  els.runtimeHotPathError.textContent = hotPathErrorCode || "-";
  els.runtimeWorkerInit.textContent = foregroundBackendSource;
  els.runtimeBackendLatency.textContent = formatLatency(backendReconcileLatencyMs);
  els.runtimeMaskedSpans.textContent = String(maskedSpanCount);
  els.runtimeQueueDepth.textContent = String(reconcileQueueDepth);
  els.runtimeDecisionSource.textContent = decisionSource;
  els.runtimeHostname.textContent = hostname;
  els.runtimeBackend.textContent = backendEndpoint;
  els.runtimeBackendState.textContent = formatBackendStatus(health, state);
}

async function refreshRuntimeState() {
  const [state, activeHostname, health] = await Promise.all([
    chrome.runtime.sendMessage({ type: "GET_LAST_PIPELINE_STATE" }),
    getActiveTabHostname().catch(() => "-"),
    chrome.runtime.sendMessage({ type: "CHECK_API_HEALTH" }).catch(() => null)
  ]);

  if (!state?.ok) {
    renderRuntimeState(null, activeHostname, health);
    return;
  }

  renderRuntimeState(state, activeHostname, health);
}

function bindMode(settings) {
  for (const input of getModeInputs()) {
    input.checked = input.value === settings.interventionMode;
    input.addEventListener("change", async () => {
      settings.interventionMode = input.value;
      await persistAndApplySettings(settings, "개입 방식 저장됨");
    });
  }
}

async function runPipelineNow() {
  if (isRunningPipeline) return;
  isRunningPipeline = true;
  setApplyButtonBusy(true);

  try {
    const response = await chrome.runtime.sendMessage({
      type: "RUN_PIPELINE_ON_ACTIVE_TAB"
    });

    if (!response?.ok) {
      renderStatus(`분석 실패: ${mapRunFailureReason(response?.reason, response?.errorCode)}`);
      return;
    }

    if (response.contentResult?.ok === false) {
      renderStatus(
        `분석 실패: ${mapRunFailureReason(
          response.contentResult.reason,
          response.contentResult.errorCode
        )}`
      );
      await refreshRuntimeState();
      return;
    }

    renderStatus("현재 탭 분석 완료");
    await refreshRuntimeState();
  } finally {
    isRunningPipeline = false;
    setApplyButtonBusy(false);
  }
}

async function initialize() {
  const settings = await loadSettings();
  let sensitivitySaveTimerId = null;

  els.enabledToggle.checked = settings.enabled;
  els.sensitivityRange.value = settings.sensitivity;
  els.sensitivityLabel.textContent = String(settings.sensitivity);
  els.catAbuse.checked = settings.categories.abuse;
  els.catHate.checked = settings.categories.hate;
  els.catInsult.checked = settings.categories.insult;
  els.catSpam.checked = settings.categories.spam;

  bindMode(settings);
  applyPreview(settings);
  await refreshRuntimeState();

  els.enabledToggle.addEventListener("change", async () => {
    settings.enabled = els.enabledToggle.checked;
    await persistAndApplySettings(settings, "필터링 상태 저장됨");
  });

  async function persistSensitivityFromControl() {
    settings.sensitivity = Number(els.sensitivityRange.value);
    await persistAndApplySettings(settings, "민감도 저장됨");
  }

  els.sensitivityRange.addEventListener("input", () => {
    els.sensitivityLabel.textContent = String(els.sensitivityRange.value);
    if (sensitivitySaveTimerId) {
      clearTimeout(sensitivitySaveTimerId);
    }
    sensitivitySaveTimerId = setTimeout(() => {
      sensitivitySaveTimerId = null;
      persistSensitivityFromControl().catch((error) => {
        renderStatus(`민감도 저장 실패: ${error?.message || error}`);
      });
    }, 180);
  });

  els.sensitivityRange.addEventListener("change", async () => {
    if (sensitivitySaveTimerId) {
      clearTimeout(sensitivitySaveTimerId);
      sensitivitySaveTimerId = null;
    }
    await persistSensitivityFromControl();
  });

  const categoryInputs = [
    ["abuse", els.catAbuse],
    ["hate", els.catHate],
    ["insult", els.catInsult],
    ["spam", els.catSpam]
  ];

  for (const [key, input] of categoryInputs) {
    input.addEventListener("change", async () => {
      settings.categories[key] = input.checked;
      await persistAndApplySettings(settings, "카테고리 저장됨");
    });
  }

  els.applyNowButton.addEventListener("click", () => {
    runPipelineNow().catch((error) => {
      console.error(error);
      renderStatus("현재 탭 분석 실패");
    });
  });

  els.openOptionsButton.addEventListener("click", () => {
    chrome.runtime.openOptionsPage();
  });

  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName !== "local") return;
    if (!changes.lastRunAt && !changes.lastStats && !changes.lastDecision && !changes.lastPipelineError) return;
    refreshRuntimeState().catch(() => {});
  });
}

initialize().catch((error) => {
  console.error(error);
  renderStatus("설정 로드 실패");
});
