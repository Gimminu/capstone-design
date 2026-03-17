const DEFAULT_SETTINGS = {
  customBlockWords: "",
  customAllowWords: "",
  blockedDomains: "",
  warnDomains: "",
  showReason: true,
  stats: {
    blockedCount: 0,
    falsePositiveCount: 0,
    averageLatencyMs: 0,
    totalAnalyzedCount: 0
  }
};

const els = {
  blockWords: document.getElementById("blockWords"),
  allowWords: document.getElementById("allowWords"),
  blockedDomains: document.getElementById("blockedDomains"),
  warnDomains: document.getElementById("warnDomains"),
  showReasonToggle: document.getElementById("showReasonToggle"),
  runNowButton: document.getElementById("runNowButton"),
  saveOptionsButton: document.getElementById("saveOptionsButton"),
  refreshJsonButton: document.getElementById("refreshJsonButton"),
  payloadPreview: document.getElementById("payloadPreview"),
  decisionPreview: document.getElementById("decisionPreview"),
  optionsStatus: document.getElementById("optionsStatus"),
  statBlocked: document.getElementById("statBlocked"),
  statFalsePositive: document.getElementById("statFalsePositive"),
  statLatency: document.getElementById("statLatency")
};

let currentSettings = null;
let isRunningPipeline = false;

function mergeSettings(stored) {
  return {
    ...stored,
    customBlockWords: stored?.customBlockWords ?? DEFAULT_SETTINGS.customBlockWords,
    customAllowWords: stored?.customAllowWords ?? DEFAULT_SETTINGS.customAllowWords,
    blockedDomains: stored?.blockedDomains ?? DEFAULT_SETTINGS.blockedDomains,
    warnDomains: stored?.warnDomains ?? DEFAULT_SETTINGS.warnDomains,
    showReason: stored?.showReason ?? DEFAULT_SETTINGS.showReason,
    stats: {
      ...DEFAULT_SETTINGS.stats,
      ...(stored?.stats || {})
    }
  };
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

function mapRunFailureReason(reason) {
  const value = String(reason || "");
  if (value.includes("UNSUPPORTED_TAB")) return "지원되지 않는 탭입니다 (chrome://, 확장 페이지 등)";
  if (value.includes("ACTIVE_TAB_NOT_FOUND")) return "현재 활성 탭을 찾지 못했습니다";
  if (value.includes("Cannot access contents of url")) return "이 페이지는 크롬 정책상 접근할 수 없습니다";
  return value || "unknown";
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
}

async function runPipelineNowFromOptions() {
  if (isRunningPipeline) return;

  isRunningPipeline = true;
  setRunNowBusy(true);

  try {
    const response = await chrome.runtime.sendMessage({ type: "RUN_PIPELINE_ON_ACTIVE_TAB" });
    if (!response?.ok) {
      renderStatus(`분석 실패: ${mapRunFailureReason(response?.reason)}`);
      return;
    }

    await loadJsonPreviews();
    renderStatus("현재 탭 분석 완료");
  } finally {
    isRunningPipeline = false;
    setRunNowBusy(false);
  }
}

function stringifyPreview(value) {
  if (!value) return "(데이터 없음)";

  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return "(JSON 직렬화 실패)";
  }
}

async function loadJsonPreviews() {
  const state = await chrome.runtime.sendMessage({ type: "GET_LAST_PIPELINE_STATE" });

  if (!state?.ok) {
    els.payloadPreview.value = "(상태 조회 실패)";
    els.decisionPreview.value = "(상태 조회 실패)";
    return;
  }

  els.payloadPreview.value = stringifyPreview(state.lastPayload);
  els.decisionPreview.value = stringifyPreview(state.lastDecision);
}

function readSettingsFromForm() {
  return {
    ...currentSettings,
    customBlockWords: els.blockWords.value.trim(),
    customAllowWords: els.allowWords.value.trim(),
    blockedDomains: els.blockedDomains.value.trim(),
    warnDomains: els.warnDomains.value.trim(),
    showReason: els.showReasonToggle.checked
  };
}

function renderSettingsToForm(settings) {
  els.blockWords.value = settings.customBlockWords;
  els.allowWords.value = settings.customAllowWords;
  els.blockedDomains.value = settings.blockedDomains;
  els.warnDomains.value = settings.warnDomains;
  els.showReasonToggle.checked = settings.showReason;
}

async function initialize() {
  const { settings } = await chrome.storage.sync.get("settings");
  currentSettings = mergeSettings(settings || {});

  renderSettingsToForm(currentSettings);
  renderStats(currentSettings.stats);
  await loadJsonPreviews();

  els.saveOptionsButton.addEventListener("click", async () => {
    currentSettings = readSettingsFromForm();
    await chrome.storage.sync.set({ settings: currentSettings });
    renderStatus("옵션 저장 완료");
    await runPipelineNowFromOptions();
  });

  els.refreshJsonButton.addEventListener("click", async () => {
    await loadJsonPreviews();
    renderStatus("최근 JSON 새로고침 완료");
  });

  els.runNowButton.addEventListener("click", async () => {
    await runPipelineNowFromOptions();
  });

  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName === "local" && (changes.lastPayload || changes.lastDecision || changes.lastRunAt)) {
      loadJsonPreviews().catch(() => {});
      return;
    }

    if (areaName === "sync" && changes.settings?.newValue) {
      currentSettings = mergeSettings(changes.settings.newValue);
      renderSettingsToForm(currentSettings);
      renderStats(currentSettings.stats);
    }
  });
}

initialize().catch((error) => {
  console.error(error);
  renderStatus("옵션 로드 실패");
});
