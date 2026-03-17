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
    byCategory: {
      abuse: 0,
      hate: 0,
      insult: 0,
      spam: 0
    },
    averageLatencyMs: 0,
    totalAnalyzedCount: 0
  }
};

function mergeSettings(stored) {
  return {
    ...DEFAULT_SETTINGS,
    ...(stored || {}),
    categories: {
      ...DEFAULT_SETTINGS.categories,
      ...(stored?.categories || {})
    },
    stats: {
      ...DEFAULT_SETTINGS.stats,
      ...(stored?.stats || {}),
      byCategory: {
        ...DEFAULT_SETTINGS.stats.byCategory,
        ...(stored?.stats?.byCategory || {})
      }
    }
  };
}

async function ensureSettings() {
  const { settings } = await chrome.storage.sync.get("settings");
  if (!settings) {
    await chrome.storage.sync.set({ settings: DEFAULT_SETTINGS });
    return;
  }

  const merged = mergeSettings(settings);
  await chrome.storage.sync.set({ settings: merged });
}

function isUnsupportedTabUrl(url) {
  const value = String(url || "").toLowerCase();
  return (
    value.startsWith("chrome://") ||
    value.startsWith("chrome-extension://") ||
    value.startsWith("edge://") ||
    value.startsWith("about:") ||
    value.startsWith("view-source:")
  );
}

async function getActiveTab() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  return tabs?.[0] || null;
}

async function runPipelineOnActiveTab() {
  const tab = await getActiveTab();
  if (!tab?.id) {
    return { ok: false, reason: "ACTIVE_TAB_NOT_FOUND" };
  }

  if (isUnsupportedTabUrl(tab.url)) {
    return { ok: false, reason: "UNSUPPORTED_TAB" };
  }

  try {
    let contentResult = null;

    try {
      contentResult = await chrome.tabs.sendMessage(tab.id, {
        type: "RUN_PIPELINE",
        reason: "manual-request"
      });
    } catch (sendError) {
      const missingReceiver = String(sendError || "").includes("Receiving end does not exist");
      if (!missingReceiver) throw sendError;

      await chrome.scripting.insertCSS({
        target: { tabId: tab.id },
        files: ["content-style.css"]
      });

      await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        files: ["content-script.js"]
      });

      contentResult = await chrome.tabs.sendMessage(tab.id, {
        type: "RUN_PIPELINE",
        reason: "manual-request-after-inject"
      });
    }

    const lastState = await chrome.storage.local.get([
      "lastPayload",
      "lastDecision",
      "lastRunAt",
      "lastStats"
    ]);

    return {
      ok: true,
      tabId: tab.id,
      tabUrl: tab.url,
      contentResult: contentResult || null,
      ...lastState
    };
  } catch (error) {
    return {
      ok: false,
      reason: String(error)
    };
  }
}

async function getLastPipelineState() {
  const state = await chrome.storage.local.get([
    "lastPayload",
    "lastDecision",
    "lastRunAt",
    "lastStats"
  ]);

  return {
    ok: true,
    ...state
  };
}

chrome.runtime.onInstalled.addListener(() => {
  ensureSettings().catch((error) => {
    console.error("[ShieldText] ensureSettings(onInstalled) failed", error);
  });
});

chrome.runtime.onStartup.addListener(() => {
  ensureSettings().catch((error) => {
    console.error("[ShieldText] ensureSettings(onStartup) failed", error);
  });
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "GET_DEFAULT_SETTINGS") {
    sendResponse({ ok: true, defaults: DEFAULT_SETTINGS });
    return true;
  }

  if (message?.type === "RUN_PIPELINE_ON_ACTIVE_TAB" || message?.type === "APPLY_FILTER_TO_ACTIVE_TAB") {
    runPipelineOnActiveTab().then(sendResponse);
    return true;
  }

  if (message?.type === "GET_LAST_PIPELINE_STATE") {
    getLastPipelineState().then(sendResponse);
    return true;
  }

  return false;
});
