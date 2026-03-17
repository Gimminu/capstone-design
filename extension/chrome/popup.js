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
  showReason: true,
  stats: {
    blockedCount: 0,
    falsePositiveCount: 0,
    averageLatencyMs: 0,
    totalAnalyzedCount: 0
  }
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
  runtimeHostname: document.getElementById("runtimeHostname")
};

let isRunningPipeline = false;

function getModeInputs() {
  return [...document.querySelectorAll('input[name="mode"]')];
}

function mergeSettings(stored) {
  return {
    ...DEFAULT_SETTINGS,
    ...stored,
    categories: {
      ...DEFAULT_SETTINGS.categories,
      ...(stored?.categories || {})
    },
    stats: {
      ...DEFAULT_SETTINGS.stats,
      ...(stored?.stats || {})
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

function mapRunFailureReason(reason) {
  const value = String(reason || "");
  if (value.includes("UNSUPPORTED_TAB")) return "지원되지 않는 탭입니다 (chrome://, 확장 페이지 등)";
  if (value.includes("ACTIVE_TAB_NOT_FOUND")) return "현재 활성 탭을 찾지 못했습니다";
  if (value.includes("Cannot access contents of url")) return "이 페이지는 크롬 정책상 접근할 수 없습니다";
  return value || "unknown";
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

async function getActiveTabHostname() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  const tab = tabs?.[0];
  if (!tab?.url) return "-";
  return safeParseHostname(tab.url);
}

function renderRuntimeState(state, fallbackHostname) {
  const analyzedNodeCount = Number(state?.lastStats?.analyzedNodeCount ?? state?.lastDecision?.analyzedNodeCount ?? 0);
  const blockedNodeCount = Number(state?.lastStats?.blockedNodeCount ?? state?.lastDecision?.blockedNodeCount ?? 0);
  const hostname = state?.lastStats?.hostname || fallbackHostname || "-";

  els.runtimeLastRun.textContent = formatTimestamp(state?.lastRunAt);
  els.runtimeAnalyzed.textContent = String(analyzedNodeCount);
  els.runtimeBlocked.textContent = String(blockedNodeCount);
  els.runtimeHostname.textContent = hostname;
}

async function refreshRuntimeState() {
  const [state, activeHostname] = await Promise.all([
    chrome.runtime.sendMessage({ type: "GET_LAST_PIPELINE_STATE" }),
    getActiveTabHostname().catch(() => "-")
  ]);

  if (!state?.ok) {
    renderRuntimeState(null, activeHostname);
    return;
  }

  renderRuntimeState(state, activeHostname);
}

function bindMode(settings) {
  for (const input of getModeInputs()) {
    input.checked = input.value === settings.interventionMode;
    input.addEventListener("change", async () => {
      settings.interventionMode = input.value;
      await saveSettings(settings);
      applyPreview(settings);
      renderStatus("개입 방식 저장됨");
      await refreshRuntimeState();
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
      renderStatus(`분석 실패: ${mapRunFailureReason(response?.reason)}`);
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
    await saveSettings(settings);
    applyPreview(settings);
    renderStatus("필터링 상태 저장됨");
    await runPipelineNow();
  });

  els.sensitivityRange.addEventListener("input", () => {
    els.sensitivityLabel.textContent = String(els.sensitivityRange.value);
  });

  els.sensitivityRange.addEventListener("change", async () => {
    settings.sensitivity = Number(els.sensitivityRange.value);
    await saveSettings(settings);
    renderStatus("민감도 저장됨");
    await runPipelineNow();
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
      await saveSettings(settings);
      applyPreview(settings);
      renderStatus("카테고리 저장됨");
      await runPipelineNow();
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
    if (!changes.lastRunAt && !changes.lastStats && !changes.lastDecision) return;
    refreshRuntimeState().catch(() => {});
  });
}

initialize().catch((error) => {
  console.error(error);
  renderStatus("설정 로드 실패");
});
