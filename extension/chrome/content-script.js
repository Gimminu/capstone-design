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
  backendApiBaseUrl: "http://127.0.0.1:8000",
  requestTimeoutMs: 10000
};

const CATEGORY_LABELS = {
  abuse: "공격",
  hate: "혐오",
  insult: "모욕",
  spam: "스팸",
  custom: "사용자"
};

const KOREAN_MASK_SUFFIXES = [
  "이라고",
  "이라도",
  "이란",
  "이라",
  "이야",
  "인데",
  "이고",
  "이나",
  "으로",
  "에게",
  "에서",
  "보다",
  "처럼",
  "아",
  "야",
  "은",
  "는",
  "이",
  "가",
  "을",
  "를",
  "도",
  "만",
  "에",
  "로",
  "과",
  "와",
  "랑",
  "여"
];

const SKIP_TAGS = new Set([
  "SCRIPT",
  "STYLE",
  "NOSCRIPT",
  "TEXTAREA",
  "INPUT",
  "BUTTON",
  "SELECT",
  "OPTION",
  "CODE",
  "PRE",
  "KBD",
  "SAMP",
  "IMG",
  "SVG",
  "CANVAS",
  "VIDEO",
  "AUDIO",
  "IFRAME",
  "FIGCAPTION"
]);

const PIPELINE_DEBOUNCE_MS = 48;
const BACKGROUND_PIPELINE_DEBOUNCE_MS = 16;
const MAX_CANDIDATES = 120;
const MAX_FOREGROUND_CONTAINERS = 5;
const MAX_BACKGROUND_CONTAINERS = 14;
const VIEWPORT_BUFFER_PX = 720;
const SCROLL_REFRESH_TEXT_NODE_LIMIT = 72;
const MAX_ANALYSIS_CONTEXT_LENGTH = 360;
const MAX_RECONCILE_CONTEXT_LENGTH = 560;
const MIN_ANALYSIS_CONTEXT_LENGTH = 24;
const MAX_ANALYSIS_CONTAINER_ASCENT = 5;
const ANALYSIS_CACHE_LIMIT = 500;
const MAX_DIRTY_TEXT_NODES_PER_MUTATION = 80;
const MAX_INITIAL_TEXT_NODES = 180;
const HOT_PATH_WORKER_TIMEOUT_MS = 90;
const HOT_PATH_WORKER_INIT_TIMEOUT_MS = 900;
const HOT_PATH_WORKER_BACKOFF_MS = 8000;
const MAX_HOT_PATH_CONTEXT_LENGTH = 320;
const INPUT_PIPELINE_DEBOUNCE_MS = 0;
const VISIBILITY_PIPELINE_DEBOUNCE_MS = 24;
const RECONCILE_FLUSH_DELAY_MS = 20;
const RECONCILE_FAST_FLUSH_DELAY_MS = 0;
const RECONCILE_CHUNK_SIZE = 2;
const MAX_FOREGROUND_CANDIDATES = 12;
const MAX_FOREGROUND_WAVE_CANDIDATES = 6;
const MAX_FOREGROUND_WAVE_CONTAINERS = 3;
const MAX_BACKGROUND_CANDIDATES = 16;
const MAX_HOT_PATH_CONTAINERS = 6;
const INITIAL_EDITABLE_PASS_LIMIT = 2;
const STARTUP_FOLLOWUP_DELAYS_MS = [48, 180, 420, 900];
const ROUTE_CHANGE_FOLLOWUP_DELAYS_MS = [80, 220, 520];
const NAVIGATION_POLL_INTERVAL_MS = 80;
const MAX_DOMAIN_PRIORITY_CANDIDATES = 6;
const MAX_GOOGLE_CANDIDATES_PER_CONTAINER = 12;
const MAX_SELF_TEST_CASES = 32;
const MAX_SELF_TEST_HISTORY = 20;
const FOREGROUND_ANALYZE_TIMEOUT_MS = 1200;
const RECONCILE_ANALYZE_TIMEOUT_MS = 3000;
const FOREGROUND_BACKEND_BATCH_SIZE = 6;
const RECONCILE_BACKEND_BATCH_SIZE = 3;
const BACKEND_WARMUP_TEXTS = ["안녕하세요", "검색 테스트", "청마루 실시간 필터"];
const FOREGROUND_STANDALONE_SAFE_CACHE_TTL_MS = 7000;
const FOREGROUND_CONTEXTUAL_SAFE_CACHE_TTL_MS = 800;
const RECONCILE_CONTEXTUAL_SAFE_CACHE_TTL_MS = 600;
const OFFENSIVE_CACHE_TTL_MS = 90000;
const DECISION_STAGE_RANK = Object.freeze({
  foreground: 1,
  reconcile: 2
});

const TEXT_NODE_ID_MAP = new WeakMap();
const NODE_STATE_BY_ID = new Map();
const EDITABLE_VALUE_ID_MAP = new WeakMap();
const EDITABLE_VALUE_STATE_BY_ID = new Map();
const DIRTY_NODE_IDS = new Set();
const VISIBLE_NODE_IDS = new Set();
const OBSERVED_ELEMENT_NODE_IDS = new WeakMap();
const ANALYSIS_CACHE = new Map();
const MASKED_EDITABLE_STATE_IDS = new Set();

const SAFE_BROWSER_UI_LABELS = new Set([
  ".github",
  ".gitignore",
  "actions",
  "activity",
  "agents",
  "android",
  "backend",
  "code",
  "contributors",
  "docs",
  "fork",
  "insights",
  "issues",
  "packages",
  "projects",
  "public",
  "pull requests",
  "readme",
  "readme.md",
  "scripts",
  "security & quality",
  "security and quality",
  "settings",
  "shared",
  "star",
  "watch",
  "wiki"
]);

const HIGH_SIGNAL_PROFANITY_PATTERN =
  /(씨[이\s]*발|시[이\s]*발|씨[이\s]*팔|시[이\s]*팔|ㅅㅂ|ㅆㅂ|병[.\s]*신|ㅂㅅ|지[이\s]*랄|ㅈㄹ|존\s*나|ㅈㄴ|좆|좇|씹|개[새세][끼키]|꺼[져저]|닥[쳐치]|죽어|뒤져|느[금끔]마|니[금끔]마|미친[놈년새]?|ssibal|sibal|tlqkf|qudtls|byungsin|gaesaekki|gaesaek|jiral|jonna|nigaumma|negeumma|fuck(?:ing|er|ed)?|shit(?:ty|head|s)?|bitch(?:es)?|ass[\s_-]*hole|bastard(?:s)?|mother[\s_-]*fucker|dick|pussy|slut|whore)/i;

let nextTextNodeId = 1;
let nextEditableValueId = 1;
let observer = null;
let visibilityObserver = null;
let debounceTimerId = null;
let isPipelineRunning = false;
let queuedReason = null;
let ignoreMutationsUntil = 0;
let latestPipelineSequence = 0;
let latestAnalysisGeneration = 0;
let cachedSettings = null;
let settingsLoadPromise = null;
let extensionContextInvalidated = false;
let realtimeWorkerStatus = "idle";
let realtimeWorkerFailure = null;
let realtimeWorkerBackoffUntil = 0;
let realtimeWorkerInitLatencyMs = 0;
let realtimeWorkerStrategy = null;
let pendingImmediateInputElement = null;
let immediateInputTimerId = null;
let overlaySyncFrameId = null;
let pendingEditableOverlaySyncFrames = 0;
let scrollVisibilityRefreshFrameId = null;
let suppressedMutationRefreshTimerId = null;
let reconcileFlushTimerId = null;
let scheduledReconcileDelayMs = 0;
let isReconcileRunning = false;
let hotPathStatsPersistTimerId = null;
let pendingHotPathStats = null;
let editableTextMeasureCanvas = null;
const RECONCILE_QUEUE = new Map();
let bootstrapStarted = false;
let bootstrapRetryTimerId = null;
let navigationListenersInitialized = false;
let routeRefreshFrameId = null;
let navigationPollTimerId = null;
let routeRefreshSequence = 0;
const ROUTE_REFRESH_TIMEOUT_IDS = new Set();
let lastObservedLocationHref = String(location.href || "");
let staleResponseDropCount = 0;
let foregroundApplyCount = 0;
let reconcileOverwriteCount = 0;
let reconcileUnmaskCount = 0;
let inputMaskResetCount = 0;
let overlayLayoutReuseCount = 0;
let overlayLayoutRebuildCount = 0;

function normalizeText(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function normalizeSensitivity(value) {
  const numberValue = Number(value);
  if (Number.isNaN(numberValue)) return DEFAULT_SETTINGS.sensitivity;
  return Math.max(0, Math.min(100, Math.round(numberValue)));
}

function normalizeLabel(value) {
  return normalizeText(value).toLowerCase();
}

function parseWordList(value) {
  return String(value || "")
    .split(/[\n,\t]+/)
    .map((item) => normalizeText(item))
    .filter(Boolean);
}

function isExtensionContextAvailable() {
  if (extensionContextInvalidated) return false;

  try {
    return Boolean(globalThis.chrome?.runtime?.id);
  } catch {
    return false;
  }
}

function isExtensionContextInvalidatedError(error) {
  const message = String(error?.message || error || "");
  return message.includes("Extension context invalidated");
}

function teardownInvalidatedExtensionContext() {
  extensionContextInvalidated = true;

  if (observer) {
    observer.disconnect();
    observer = null;
  }
  if (visibilityObserver) {
    visibilityObserver.disconnect();
    visibilityObserver = null;
  }
  if (debounceTimerId) {
    window.clearTimeout(debounceTimerId);
    debounceTimerId = null;
  }
  if (reconcileFlushTimerId) {
    window.clearTimeout(reconcileFlushTimerId);
    reconcileFlushTimerId = null;
  }
  if (hotPathStatsPersistTimerId) {
    window.clearTimeout(hotPathStatsPersistTimerId);
    hotPathStatsPersistTimerId = null;
  }
  if (immediateInputTimerId) {
    window.cancelAnimationFrame(immediateInputTimerId);
    immediateInputTimerId = null;
  }
  if (overlaySyncFrameId) {
    window.cancelAnimationFrame(overlaySyncFrameId);
    overlaySyncFrameId = null;
  }
  if (bootstrapRetryTimerId) {
    window.clearTimeout(bootstrapRetryTimerId);
    bootstrapRetryTimerId = null;
  }
  if (routeRefreshFrameId) {
    window.cancelAnimationFrame(routeRefreshFrameId);
    routeRefreshFrameId = null;
  }
  if (suppressedMutationRefreshTimerId) {
    window.clearTimeout(suppressedMutationRefreshTimerId);
    suppressedMutationRefreshTimerId = null;
  }
  if (navigationPollTimerId) {
    window.clearInterval(navigationPollTimerId);
    navigationPollTimerId = null;
  }
  for (const timeoutId of ROUTE_REFRESH_TIMEOUT_IDS) {
    window.clearTimeout(timeoutId);
  }
  ROUTE_REFRESH_TIMEOUT_IDS.clear();

  cleanupRealtimeWorker();
}

function handleExtensionContextError(error) {
  if (!isExtensionContextInvalidatedError(error)) {
    return false;
  }

  teardownInvalidatedExtensionContext();
  console.warn("[청마루] extension context invalidated");
  return true;
}

async function safeStorageSyncGet(keys) {
  if (!isExtensionContextAvailable()) return {};

  try {
    return await chrome.storage.sync.get(keys);
  } catch (error) {
    if (handleExtensionContextError(error)) {
      return {};
    }
    throw error;
  }
}

async function safeStorageLocalGet(keys) {
  if (!isExtensionContextAvailable()) return {};

  try {
    return await chrome.storage.local.get(keys);
  } catch (error) {
    if (handleExtensionContextError(error)) {
      return {};
    }
    throw error;
  }
}

async function safeStorageSyncSet(value) {
  if (!isExtensionContextAvailable()) return;

  try {
    await chrome.storage.sync.set(value);
  } catch (error) {
    if (!handleExtensionContextError(error)) {
      throw error;
    }
  }
}

async function safeStorageLocalSet(value) {
  if (!isExtensionContextAvailable()) return;

  try {
    await chrome.storage.local.set(value);
  } catch (error) {
    if (!handleExtensionContextError(error)) {
      throw error;
    }
  }
}

async function safeRuntimeSendMessage(message) {
  if (!isExtensionContextAvailable()) return null;

  try {
    return await chrome.runtime.sendMessage(message);
  } catch (error) {
    if (handleExtensionContextError(error)) {
      return null;
    }
    throw error;
  }
}

function getRuntimeUrl(path) {
  if (!isExtensionContextAvailable()) {
    throw new Error("EXTENSION_CONTEXT_INVALIDATED");
  }

  return chrome.runtime.getURL(path);
}

function isSpeculationRulesElement(element) {
  return (
    element instanceof HTMLScriptElement &&
    String(element.type || "").toLowerCase() === "speculationrules"
  );
}

function isShieldTextManagedElement(element) {
  if (!(element instanceof Element)) {
    return false;
  }

  return Boolean(
    element.closest(
      ".shieldtext-editable-overlay, .shieldtext-site-policy-overlay, [data-shieldtext-rendered='true'], [data-shieldtext-wrapper='true'], [data-shieldtext-overlay='true']"
    )
  );
}

// runtime-status helpers are loaded from content-runtime-status.js

function isUnsupportedPage() {
  if (!document.body) return true;
  if (!location || !location.href) return true;
  if (location.protocol === "chrome:" || location.protocol === "chrome-extension:") return true;
  if ((document.contentType || "").toLowerCase().includes("pdf")) return true;
  return false;
}

function getMergedSettings(storedSettings) {
  return {
    ...DEFAULT_SETTINGS,
    ...(storedSettings || {}),
    categories: {
      ...DEFAULT_SETTINGS.categories,
      ...(storedSettings?.categories || {})
    }
  };
}

function updateCachedSettings(storedSettings) {
  cachedSettings = getMergedSettings(storedSettings || {});
  return cachedSettings;
}

async function loadSettings(options = {}) {
  if (!isExtensionContextAvailable()) {
    return getMergedSettings(cachedSettings || {});
  }

  if (!options.force && cachedSettings) {
    return cachedSettings;
  }

  if (!options.force && settingsLoadPromise) {
    return settingsLoadPromise;
  }

  settingsLoadPromise = safeStorageSyncGet("settings")
    .then(({ settings }) => updateCachedSettings(settings || {}))
    .finally(() => {
      settingsLoadPromise = null;
    });

  return settingsLoadPromise;
}

function isElementVisible(element) {
  if (!(element instanceof Element)) return false;

  const style = window.getComputedStyle(element);
  if (style.display === "none" || style.visibility === "hidden") return false;
  if (Number(style.opacity) === 0) return false;

  const rect = element.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return false;

  return true;
}

function isElementNearViewport(rect) {
  return rect.bottom >= -VIEWPORT_BUFFER_PX && rect.top <= window.innerHeight + VIEWPORT_BUFFER_PX;
}

function isEditableElement(element) {
  if (!(element instanceof Element)) return false;
  if (element.isContentEditable) return true;
  if (element.closest("[contenteditable='true']")) return true;

  const tagName = element.tagName;
  return tagName === "INPUT" || tagName === "TEXTAREA" || tagName === "SELECT";
}

function getGoogleInteractiveRoot(element) {
  if (!isGoogleSearchPage() || !(element instanceof Element)) {
    return null;
  }

  return element.closest("button, [role='button'], a[href]");
}

function shouldAllowGoogleInteractiveElement(element) {
  const interactiveRoot = getGoogleInteractiveRoot(element);
  if (!(interactiveRoot instanceof Element)) {
    return false;
  }

  if (
    interactiveRoot.closest(
      "header, nav, [role='navigation'], [role='tablist'], [aria-label='탐색'], form"
    )
  ) {
    return false;
  }

  if (!interactiveRoot.closest("#search, main, [role='main'], #rhs")) {
    return false;
  }

  const text = normalizeText(interactiveRoot.innerText || interactiveRoot.textContent || "");
  if (!text || !isCandidateTextUseful(text, interactiveRoot)) {
    return false;
  }

  return HIGH_SIGNAL_PROFANITY_PATTERN.test(text);
}

function isSkippableElement(element) {
  if (!(element instanceof Element)) return true;
  const allowGoogleInteractive = shouldAllowGoogleInteractiveElement(element);
  if (SKIP_TAGS.has(element.tagName) && !(element.tagName === "BUTTON" && allowGoogleInteractive)) {
    return true;
  }
  if (isShieldTextManagedElement(element)) return true;
  if (isEditableElement(element)) return true;
  if (element.closest("form") && !allowGoogleInteractive) return true;
  if (element.closest("pre, code, textarea, input, select")) return true;
  if (element.closest("button, [role='button']") && !allowGoogleInteractive) {
    return true;
  }
  if (element.closest("[data-shieldtext-rendered='true']")) return true;
  if (element.getAttribute("role") === "button" && !allowGoogleInteractive) return true;
  if (element.getAttribute("role") === "textbox") return true;
  return false;
}

function shouldSkipTextNodeParent(element) {
  return isSkippableElement(element) || isSpeculationRulesElement(element);
}

function looksLikeRawUrl(text) {
  const compact = String(text || "").replace(/\s+/g, "");
  if (!compact) return false;
  if (compact.includes("://")) return true;
  if (/^www\./i.test(compact)) return true;
  if (/^[\w.-]+\.(com|net|org|kr|co|io|me|wiki)(\/\S*)?$/i.test(compact)) return true;
  return false;
}

function isCandidateTextUseful(text, element) {
  const normalizedText = normalizeText(text);
  if (!normalizedText) return false;
  if (/^[\d\s.,\-:/|]+$/.test(normalizedText)) return false;
  if (looksLikeRawUrl(normalizedText)) return false;
  if (SAFE_BROWSER_UI_LABELS.has(normalizeLabel(normalizedText))) return false;
  if (HIGH_SIGNAL_PROFANITY_PATTERN.test(normalizedText)) return true;
  if (normalizedText.length < 2) return false;

  if (element instanceof Element) {
    const tagName = element.tagName;
    if (tagName === "CITE") return false;
    if (tagName === "A" && looksLikeRawUrl(normalizedText)) return false;
    if (element.closest("cite")) return false;
  }

  return true;
}

function getTextNodeId(textNode) {
  let nodeId = TEXT_NODE_ID_MAP.get(textNode);
  if (!nodeId) {
    nodeId = `text-node-${nextTextNodeId++}`;
    TEXT_NODE_ID_MAP.set(textNode, nodeId);
  }
  return nodeId;
}

function isMaskableValueElement(element) {
  if (!(element instanceof Element)) return false;
  const isNativeTextField =
    element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement;

  if (!isNativeTextField) {
    return false;
  }

  if (!isElementVisible(element)) return false;
  if (!isElementNearViewport(element.getBoundingClientRect())) return false;
  if ("disabled" in element && element.disabled) return false;

  if (element instanceof HTMLInputElement) {
    const inputType = (element.type || "text").toLowerCase();
    if (!["text", "search", ""].includes(inputType)) {
      return false;
    }
  }

  return normalizeText(getEditableElementText(element)).length > 0;
}

function getEditableElementText(element) {
  if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) {
    return String(element.value || "");
  }

  return String(element.innerText || element.textContent || "");
}

function getEditableValueId(element) {
  let nodeId = EDITABLE_VALUE_ID_MAP.get(element);
  if (!nodeId) {
    nodeId = `editable-value-${nextEditableValueId++}`;
    EDITABLE_VALUE_ID_MAP.set(element, nodeId);
  }
  return nodeId;
}

function getEditableValueState(element) {
  const nodeId = getEditableValueId(element);
  let state = EDITABLE_VALUE_STATE_BY_ID.get(nodeId);

  if (!state) {
    state = {
      nodeId,
      element,
      hasProcessed: false,
      lastFingerprint: "",
      lastDecisionKey: "",
      lastAppliedFingerprint: "",
      lastAppliedStage: "",
      lastAppliedBlocked: false,
      lastReconcileFingerprint: "",
      analysisGeneration: 0,
      isMasked: false,
      isPending: false,
      originalTitle: element.getAttribute("title") || "",
      originalColor: element.style.color || "",
      originalWebkitTextFillColor: element.style.webkitTextFillColor || "",
      originalCaretColor: element.style.caretColor || "",
      originalTextShadow: element.style.textShadow || "",
      originalWebkitTextSecurity: element.style.webkitTextSecurity || "",
      originalTextSecurity: element.style.textSecurity || "",
      overlayRoot: null,
      overlayContent: null,
      overlayMode: "",
      overlayHost: null,
      overlayHostPositionPatched: false,
      overlayHostOriginalPosition: "",
      maskedText: "",
      maskedSpans: [],
      overlayTooltip: "",
      overlayRenderKey: "",
      overlayLayoutKey: "",
      overlayBarsKey: "",
      nativeMaskApplied: false
    };
    EDITABLE_VALUE_STATE_BY_ID.set(nodeId, state);
  } else {
    state.element = element;
  }

  return state;
}

function getNodeState(textNode) {
  const nodeId = getTextNodeId(textNode);
  let state = NODE_STATE_BY_ID.get(nodeId);

  if (!state) {
    state = {
      nodeId,
      textNode,
      wrapper: null,
      originalText: String(textNode.nodeValue || ""),
      hasProcessed: false,
      lastFingerprint: "",
      lastDecisionKey: "",
      lastAppliedFingerprint: "",
      lastAppliedStage: "",
      lastAppliedBlocked: false,
      lastReconcileFingerprint: "",
      analysisGeneration: 0,
      isMasked: false,
      isPending: false,
      observedElement: null
    };
    NODE_STATE_BY_ID.set(nodeId, state);
  } else {
    state.textNode = textNode;
    if (!state.wrapper && textNode.parentElement?.dataset?.shieldtextWrapper === "true") {
      state.wrapper = textNode.parentElement;
    }
  }

  return state;
}

function getRenderableParent(textNode) {
  if (!(textNode instanceof Text)) return null;
  let parent = textNode.parentElement;
  if (!parent) return null;

  if (parent.dataset?.shieldtextWrapper === "true") {
    parent = parent.parentElement;
  }

  return parent instanceof Element ? parent : null;
}

function isBlockLikeElement(element) {
  if (!(element instanceof Element)) return false;
  const display = window.getComputedStyle(element).display;
  return (
    display === "block" ||
    display === "flex" ||
    display === "grid" ||
    display === "list-item" ||
    display === "table" ||
    display === "table-row" ||
    display === "table-cell"
  );
}

function getElementReadableText(element) {
  if (!(element instanceof Element)) return "";
  return normalizeText(element.innerText || element.textContent || "");
}

function getGoogleSearchAnalysisContainer(element) {
  if (!isGoogleSearchPage() || !(element instanceof Element)) {
    return null;
  }

  return (
    element.closest(
      "#search .MjjYud, #search .g, #search .tF2Cxc, #search .yuRUbf, #search .ULSxyf, #rhs [data-attrid], #rhs .kp-wholepage, #rhs"
    ) ||
    null
  );
}

function isExcludedGoogleAnalysisContainer(element) {
  if (!(element instanceof Element)) {
    return true;
  }

  return Boolean(element.closest("g-scrolling-carousel"));
}

function getGoogleVisibleAnalysisContainers(limit = MAX_HOT_PATH_CONTAINERS) {
  if (!isGoogleSearchPage()) {
    return [];
  }

  const selectors = [
    "#search .MjjYud",
    "#search .g",
    "#search .tF2Cxc",
    "#search .ULSxyf",
    "#rhs [data-attrid]",
    "#rhs .kp-wholepage",
    "#rhs"
  ];
  const containers = [];
  const seenContainers = new Set();

  for (const selector of selectors) {
    for (const element of document.querySelectorAll(selector)) {
      if (!(element instanceof Element)) continue;

      const container = getGoogleSearchAnalysisContainer(element) || element;
      if (!(container instanceof Element)) continue;
      if (seenContainers.has(container)) continue;
      if (isExcludedGoogleAnalysisContainer(container)) continue;
      if (!container.isConnected || !isElementVisible(container)) continue;
      if (!isElementNearViewport(container.getBoundingClientRect())) continue;

      seenContainers.add(container);
      containers.push(container);
    }
  }

  containers.sort((left, right) => {
    const leftRect = left.getBoundingClientRect();
    const rightRect = right.getBoundingClientRect();
    if (leftRect.top !== rightRect.top) {
      return leftRect.top - rightRect.top;
    }
    return leftRect.left - rightRect.left;
  });

  return containers.slice(0, limit);
}

function getAnalysisContainer(element) {
  if (!(element instanceof Element)) return null;

  const googleContainer = getGoogleSearchAnalysisContainer(element);
  if (googleContainer) {
    return googleContainer;
  }

  const elementText = getElementReadableText(element);
  let fallback = element;
  let current = element;

  for (let depth = 0; depth < MAX_ANALYSIS_CONTAINER_ASCENT && current && current !== document.body; depth += 1) {
    const currentText = getElementReadableText(current);
    if (!currentText) break;

    if (currentText.length <= MAX_ANALYSIS_CONTEXT_LENGTH) {
      fallback = current;
    }

    const hasMeaningfulContext =
      currentText.length >= Math.max(MIN_ANALYSIS_CONTEXT_LENGTH, elementText.length + 8) &&
      currentText.length <= MAX_ANALYSIS_CONTEXT_LENGTH;

    if (hasMeaningfulContext && isBlockLikeElement(current)) {
      return current;
    }

    current = current.parentElement;
  }

  return fallback;
}

function getSourceText(state) {
  const liveText = String(state?.textNode?.nodeValue ?? "");
  if (liveText || (!state.isMasked && !state.isPending)) {
    state.originalText = liveText;
    return liveText;
  }

  return String(state.originalText || "");
}

function buildFingerprint(text) {
  return normalizeText(text);
}

function unlinkObservedElement(state) {
  if (!state?.observedElement) return;

  const linkedNodeIds = OBSERVED_ELEMENT_NODE_IDS.get(state.observedElement);
  if (linkedNodeIds) {
    linkedNodeIds.delete(state.nodeId);
    if (linkedNodeIds.size === 0) {
      visibilityObserver?.unobserve(state.observedElement);
    }
  }

  VISIBLE_NODE_IDS.delete(state.nodeId);
  state.observedElement = null;
}

function linkObservedElement(state, element) {
  if (!state || !(element instanceof Element)) return;
  if (state.observedElement === element) {
    if (isElementVisible(element) && isElementNearViewport(element.getBoundingClientRect())) {
      VISIBLE_NODE_IDS.add(state.nodeId);
    }
    return;
  }

  unlinkObservedElement(state);

  let linkedNodeIds = OBSERVED_ELEMENT_NODE_IDS.get(element);
  if (!linkedNodeIds) {
    linkedNodeIds = new Set();
    OBSERVED_ELEMENT_NODE_IDS.set(element, linkedNodeIds);
    visibilityObserver?.observe(element);
  }
  linkedNodeIds.add(state.nodeId);
  state.observedElement = element;

  if (isElementVisible(element) && isElementNearViewport(element.getBoundingClientRect())) {
    VISIBLE_NODE_IDS.add(state.nodeId);
  }
}

function syncObservedElement(state) {
  if (!state?.textNode?.isConnected) {
    unlinkObservedElement(state);
    return null;
  }

  const element = getRenderableParent(state.textNode);
  if (!element || isSkippableElement(element)) {
    unlinkObservedElement(state);
    return null;
  }

  linkObservedElement(state, element);
  return element;
}

function registerTextNode(textNode, options = {}) {
  if (!(textNode instanceof Text)) return null;
  const state = getNodeState(textNode);
  const element = syncObservedElement(state);
  if (!element) return null;

  getSourceText(state);
  if (options.markDirty) {
    DIRTY_NODE_IDS.add(state.nodeId);
  }

  return state;
}

function registerTextNodesInTree(root, options = {}) {
  const limit = Number.isFinite(options.limit) ? options.limit : Number.POSITIVE_INFINITY;
  const onlyVisible = Boolean(options.onlyVisible);
  let registeredCount = 0;

  function registerAndCount(textNode) {
    if (registeredCount >= limit) return;
    if (registerTextNode(textNode, options)) {
      registeredCount += 1;
    }
  }

  if (root instanceof Text) {
    registerAndCount(root);
    return registeredCount;
  }

  if (!(root instanceof Element) && !(root instanceof DocumentFragment) && root !== document.body) {
    return registeredCount;
  }

  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      if (!(node instanceof Text)) return NodeFilter.FILTER_REJECT;
      if (!node.parentElement) return NodeFilter.FILTER_REJECT;
      if (shouldSkipTextNodeParent(node.parentElement)) {
        return NodeFilter.FILTER_REJECT;
      }
      if (onlyVisible) {
        if (!isElementVisible(node.parentElement)) return NodeFilter.FILTER_REJECT;
        if (!isElementNearViewport(node.parentElement.getBoundingClientRect())) {
          return NodeFilter.FILTER_REJECT;
        }
      }
      return NodeFilter.FILTER_ACCEPT;
    }
  });

  while (walker.nextNode()) {
    if (registeredCount >= limit) break;
    registerAndCount(walker.currentNode);
  }

  return registeredCount;
}

function cleanupDisconnectedStates() {
  for (const [nodeId, state] of NODE_STATE_BY_ID.entries()) {
    const textConnected = Boolean(state.textNode?.isConnected);
    const wrapperConnected = Boolean(state.wrapper?.isConnected);
    if (!textConnected && !wrapperConnected) {
      unlinkObservedElement(state);
      NODE_STATE_BY_ID.delete(nodeId);
      DIRTY_NODE_IDS.delete(nodeId);
      VISIBLE_NODE_IDS.delete(nodeId);
    }
  }

  for (const [nodeId, state] of EDITABLE_VALUE_STATE_BY_ID.entries()) {
    if (!state.element?.isConnected) {
      if (state.overlayRoot?.isConnected) {
        state.overlayRoot.remove();
      }
      EDITABLE_VALUE_STATE_BY_ID.delete(nodeId);
      DIRTY_NODE_IDS.delete(nodeId);
      VISIBLE_NODE_IDS.delete(nodeId);
      MASKED_EDITABLE_STATE_IDS.delete(nodeId);
    }
  }
}

function buildCandidateFromState(state) {
  if (!state?.textNode?.isConnected) return null;

  const element = syncObservedElement(state);
  if (!element || !VISIBLE_NODE_IDS.has(state.nodeId)) return null;
  if (!isElementVisible(element)) {
    VISIBLE_NODE_IDS.delete(state.nodeId);
    return null;
  }

  const rect = element.getBoundingClientRect();
  if (!isElementNearViewport(rect)) {
    return null;
  }

  const text = getSourceText(state);
  const normalizedText = normalizeText(text);
  if (!isCandidateTextUseful(normalizedText, element)) {
    return null;
  }

  const analysisContainer = getAnalysisContainer(element) || element;

  return {
    nodeId: state.nodeId,
    textNode: state.textNode,
    state,
    element,
    text,
    normalizedText: normalizedText.toLowerCase(),
    analysisContainer,
    packageName: `web::${location.hostname || "unknown"}`,
    className:
      typeof element.className === "string" && element.className.trim()
        ? element.className.trim()
        : element.tagName,
    top: Math.round(rect.top + window.scrollY),
    bottom: Math.round(rect.bottom + window.scrollY),
    left: Math.round(rect.left + window.scrollX),
    right: Math.round(rect.right + window.scrollX),
    distanceFromViewport: Math.abs(rect.top),
    fingerprint: buildFingerprint(normalizedText)
  };
}

function buildForcedVisibleCandidateFromTextNode(textNode) {
  if (!(textNode instanceof Text) || !textNode.isConnected) return null;

  const state = getNodeState(textNode);
  const element = syncObservedElement(state);
  if (!element || !isElementVisible(element)) {
    return null;
  }

  const rect = element.getBoundingClientRect();
  if (!isElementNearViewport(rect)) {
    return null;
  }

  VISIBLE_NODE_IDS.add(state.nodeId);
  return buildCandidateFromState(state);
}

function buildEditableValueCandidate(element) {
  if (!isMaskableValueElement(element)) return null;

  const state = getEditableValueState(element);
  const analysisContainer = element;
  const text = getEditableElementText(element);
  const normalizedText = normalizeText(text);
  if (!normalizedText) return null;

  const rect = element.getBoundingClientRect();
  const nodeId = state.nodeId;
  VISIBLE_NODE_IDS.add(nodeId);

  return {
    nodeId,
    candidateKind: "editable-value",
    element,
    state,
    text,
    normalizedText: normalizedText.toLowerCase(),
    analysisContainer,
    packageName: `web::${location.hostname || "unknown"}`,
    className:
      typeof element.className === "string" && element.className.trim()
        ? element.className.trim()
        : element.tagName,
    top: Math.round(rect.top + window.scrollY),
    bottom: Math.round(rect.bottom + window.scrollY),
    left: Math.round(rect.left + window.scrollX),
    right: Math.round(rect.right + window.scrollX),
    distanceFromViewport: Math.abs(rect.top),
    fingerprint: buildFingerprint(normalizedText)
  };
}

function getEditableCandidatePriority(candidate) {
  if (!candidate) return Number.NEGATIVE_INFINITY;

  let score = 0;
  if (candidate.element === pendingImmediateInputElement) {
    score += 120;
  }
  if (candidate.element === document.activeElement) {
    score += 100;
  }
  if (
    candidate.element instanceof HTMLInputElement &&
    (candidate.element.type || "text").toLowerCase() === "search"
  ) {
    score += 28;
  }
  if ((candidate.element?.getAttribute("role") || "").toLowerCase() === "searchbox") {
    score += 20;
  }

  score += Math.max(0, 360 - Number(candidate.distanceFromViewport || 0)) / 8;
  score += Math.min(18, normalizeText(candidate.text).length / 3);
  return score;
}

function collectEditableValueCandidates(limit = Number.POSITIVE_INFINITY) {
  const elements = document.querySelectorAll("input, textarea");
  const candidates = [];

  for (const element of elements) {
    const candidate = buildEditableValueCandidate(element);
    if (candidate) {
      candidates.push(candidate);
    }
  }

  candidates.sort((left, right) => {
    const priorityGap = getEditableCandidatePriority(right) - getEditableCandidatePriority(left);
    if (priorityGap !== 0) return priorityGap;
    if (left.distanceFromViewport !== right.distanceFromViewport) {
      return left.distanceFromViewport - right.distanceFromViewport;
    }
    return left.top - right.top;
  });

  return Number.isFinite(limit) ? candidates.slice(0, limit) : candidates;
}

function collectTextCandidatesFromElements(elements, limit = Number.POSITIVE_INFINITY, options = {}) {
  const candidates = [];
  const seenNodeIds = new Set();
  const candidateFilter =
    typeof options.candidateFilter === "function" ? options.candidateFilter : null;
  const perElementLimit = Math.max(
    1,
    Number.isFinite(options.perElementLimit)
      ? Number(options.perElementLimit)
      : Number.POSITIVE_INFINITY
  );

  for (const element of elements) {
    if (!(element instanceof Element)) continue;
    if (!element.isConnected || !isElementVisible(element)) continue;
    if (!isElementNearViewport(element.getBoundingClientRect())) continue;

    registerTextNodesInTree(element, {
      onlyVisible: true,
      limit: 24
    });

    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        if (!(node instanceof Text)) return NodeFilter.FILTER_REJECT;
        if (!node.parentElement) return NodeFilter.FILTER_REJECT;
        if (shouldSkipTextNodeParent(node.parentElement)) {
          return NodeFilter.FILTER_REJECT;
        }
        return NodeFilter.FILTER_ACCEPT;
      }
    });

    let perElementCount = 0;
    while (walker.nextNode()) {
      if (candidates.length >= limit) {
        return candidates;
      }
      if (perElementCount >= perElementLimit) {
        break;
      }

      const candidate = buildForcedVisibleCandidateFromTextNode(walker.currentNode);
      if (!candidate) continue;
      if (candidateFilter && !candidateFilter(candidate, element)) continue;
      if (seenNodeIds.has(candidate.nodeId)) continue;

      seenNodeIds.add(candidate.nodeId);
      candidates.push(candidate);
      perElementCount += 1;
    }
  }

  return candidates;
}

function collectGoogleSearchPriorityContainerCandidates(limit = MAX_DOMAIN_PRIORITY_CANDIDATES) {
  if (!isGoogleSearchPage()) {
    return [];
  }

  const containerLimit = Math.max(MAX_HOT_PATH_CONTAINERS, Number(limit || 0));
  const containers = getGoogleVisibleAnalysisContainers(containerLimit);
  return collectTextCandidatesFromElements(
    containers,
    Math.max(1, containers.length) * MAX_GOOGLE_CANDIDATES_PER_CONTAINER,
    {
      perElementLimit: Math.min(4, MAX_GOOGLE_CANDIDATES_PER_CONTAINER)
    }
  );
}

function collectGoogleHighSignalInteractiveCandidates(limit = MAX_DOMAIN_PRIORITY_CANDIDATES) {
  if (!isGoogleSearchPage()) {
    return [];
  }

  const selectors = [
    "#search button",
    "#search [role='button']",
    "#search a[href]",
    "#bres button",
    "#bres [role='button']",
    "#bres a[href]",
    "#botstuff button",
    "#botstuff [role='button']",
    "#botstuff a[href]",
    "#rhs button",
    "#rhs [role='button']",
    "#rhs a[href]"
  ];
  const elements = [];
  const seenElements = new Set();

  for (const selector of selectors) {
    for (const element of document.querySelectorAll(selector)) {
      if (!(element instanceof Element)) continue;
      if (seenElements.has(element)) continue;
      if (!element.isConnected || !isElementVisible(element)) continue;
      if (!isElementNearViewport(element.getBoundingClientRect())) continue;

      const text = normalizeText(element.innerText || element.textContent || "");
      if (!text || !HIGH_SIGNAL_PROFANITY_PATTERN.test(text)) continue;
      if (SAFE_BROWSER_UI_LABELS.has(normalizeLabel(text))) continue;

      seenElements.add(element);
      elements.push(element);
      if (elements.length >= limit * 3) {
        break;
      }
    }

    if (elements.length >= limit * 3) {
      break;
    }
  }

  return collectTextCandidatesFromElements(elements, limit * 2, {
    perElementLimit: 3,
    candidateFilter(candidate) {
      const text = normalizeText(candidate.text);
      return Boolean(text) && HIGH_SIGNAL_PROFANITY_PATTERN.test(text);
    }
  });
}

function collectGoogleSearchPriorityCandidates(limit = MAX_DOMAIN_PRIORITY_CANDIDATES) {
  if (!isGoogleSearchPage()) {
    return [];
  }

  const selectors = [
    "#search h3",
    "#search [role='heading']",
    "#search [aria-level='3']",
    "#search a[href] h3",
    "#search .LC20lb",
    "#search .DKV0Md",
    "#search .VwiC3b",
    "#search .MUxGbd",
    "#search .yXK7lf",
    "#search [data-sncf]",
    "#search [data-snf]",
    "#search button",
    "#search [role='button']",
    "#bres button",
    "#bres [role='button']",
    "#bres a[href]",
    "#botstuff button",
    "#botstuff [role='button']",
    "#botstuff a[href]",
    "#rhs button",
    "#rhs [role='button']"
  ];

  const elements = [];
  const seenElements = new Set();
  for (const selector of selectors) {
    for (const element of document.querySelectorAll(selector)) {
      if (!(element instanceof Element)) continue;
      if (seenElements.has(element)) continue;
      seenElements.add(element);
      elements.push(element);
      if (elements.length >= limit * 2) {
        break;
      }
    }
    if (elements.length >= limit * 2) {
      break;
    }
  }

  const candidates = [];
  const seenNodeIds = new Set();

  for (const candidate of collectGoogleHighSignalInteractiveCandidates(limit)) {
    if (seenNodeIds.has(candidate.nodeId)) continue;
    seenNodeIds.add(candidate.nodeId);
    candidates.push(candidate);
    if (candidates.length >= limit * 2) {
      return candidates;
    }
  }

  for (const candidate of collectGoogleSearchPriorityContainerCandidates(limit)) {
    if (seenNodeIds.has(candidate.nodeId)) continue;
    seenNodeIds.add(candidate.nodeId);
    candidates.push(candidate);
    if (candidates.length >= limit * 2) {
      return candidates;
    }
  }

  for (const candidate of collectTextCandidatesFromElements(elements, limit)) {
    if (seenNodeIds.has(candidate.nodeId)) continue;
    seenNodeIds.add(candidate.nodeId);
    candidates.push(candidate);
    if (candidates.length >= limit * 2) {
      break;
    }
  }

  return candidates;
}

function collectCandidates() {
  cleanupDisconnectedStates();

  const candidates = [];
  const seenNodeIds = new Set();

  for (const candidate of collectGoogleSearchPriorityCandidates()) {
    if (seenNodeIds.has(candidate.nodeId)) continue;
    seenNodeIds.add(candidate.nodeId);
    candidates.push(candidate);
  }

  for (const nodeId of VISIBLE_NODE_IDS) {
    const state = NODE_STATE_BY_ID.get(nodeId);
    const candidate = buildCandidateFromState(state);
    if (candidate) {
      if (seenNodeIds.has(candidate.nodeId)) continue;
      seenNodeIds.add(candidate.nodeId);
      candidates.push(candidate);
    }
  }

  const editableElements = [];
  if (pendingImmediateInputElement instanceof Element) {
    editableElements.push(pendingImmediateInputElement);
  }
  if (
    document.activeElement instanceof Element &&
    document.activeElement !== pendingImmediateInputElement
  ) {
    editableElements.push(document.activeElement);
  }

  for (const element of editableElements) {
    const candidate = buildEditableValueCandidate(element);
    if (!candidate || seenNodeIds.has(candidate.nodeId)) continue;
    seenNodeIds.add(candidate.nodeId);
    candidates.push(candidate);
  }

  candidates.sort((left, right) => {
    if (left.distanceFromViewport !== right.distanceFromViewport) {
      return left.distanceFromViewport - right.distanceFromViewport;
    }

    return left.top - right.top;
  });

  return candidates.slice(0, MAX_CANDIDATES);
}

function isBroadAnalysisReason(runReason) {
  return (
    runReason === "background-validation" ||
    runReason === "manual-request" ||
    runReason === "manual-request-after-inject" ||
    runReason === "manual" ||
    runReason === "settings-updated"
  );
}

function containsAnyWord(text, words) {
  if (!text || !Array.isArray(words) || words.length === 0) return false;
  return words.some((word) => word && text.includes(word.toLowerCase()));
}

function buildRealtimeHints(settings) {
  return {
    allowWords: parseWordList(settings?.customAllowWords).map((item) => item.toLowerCase()),
    blockWords: parseWordList(settings?.customBlockWords).map((item) => item.toLowerCase())
  };
}

function getCandidateUrgency(candidate, hints) {
  const loweredText = normalizeText(candidate?.text || "").toLowerCase();
  if (!loweredText) return 0;

  if (containsAnyWord(loweredText, hints?.allowWords)) {
    return 0;
  }

  let score = 0;

  if (candidate?.candidateKind === "editable-value") {
    score += 6;
  }

  if (shouldPreferStandaloneAnalysis(candidate)) {
    score += 8;
  }

  if (isGooglePriorityCandidate(candidate)) {
    score += 6;
  }

  const element = candidate?.element;
  if (element instanceof Element) {
    if (element.matches("h3, [role='heading']") || element.closest("h3, [role='heading']")) {
      score += 8;
    }
    if (
      element.closest(".VwiC3b, .MUxGbd, [data-sncf], [data-snf], [data-content-feature='1']")
    ) {
      score += 3;
    }
  }

  if (containsAnyWord(loweredText, hints?.blockWords)) {
    score += 12;
  }

  if (HIGH_SIGNAL_PROFANITY_PATTERN.test(loweredText)) {
    score += 10;
  }

  if (/[ㄱ-ㅎㅏ-ㅣ가-힣]/.test(loweredText)) {
    score += 1;
  }

  return score;
}

function sortCandidatesByUrgency(candidates, hints) {
  return [...candidates].sort((left, right) => {
    const scoreGap = getCandidateUrgency(right, hints) - getCandidateUrgency(left, hints);
    if (scoreGap !== 0) return scoreGap;
    if (left.distanceFromViewport !== right.distanceFromViewport) {
      return left.distanceFromViewport - right.distanceFromViewport;
    }
    return left.top - right.top;
  });
}

function buildPayload(processedCandidates, totalCandidateCount, droppedCandidateCount) {
  return {
    commentCandidates: [],
    packageName: `web::${location.hostname || "unknown"}`,
    rawTextNodes: processedCandidates.map((item) => ({
      nodeId: item.nodeId,
      approxTop: item.top,
      top: item.top,
      bottom: item.bottom,
      left: item.left,
      right: item.right,
      className: item.className,
      packageName: item.packageName,
      isVisibleToUser: true,
      text: item.text,
      displayText: item.text,
      contentDescription: item.text
    })),
    timestamp: Date.now(),
    totalCandidateCount,
    selectedCandidateCount: processedCandidates.length,
    droppedCandidateCount
  };
}

function selectForegroundCandidatesByContainer(candidates, containerLimit) {
  const selected = [];
  const selectedContainers = new Set();

  for (const candidate of candidates) {
    const container = candidate.analysisContainer || candidate.element;
    if (!container) continue;

    if (!selectedContainers.has(container)) {
      if (selectedContainers.size >= containerLimit) {
        continue;
      }
      selectedContainers.add(container);
    }

    selected.push(candidate);
  }

  return selected;
}

function isShortHighSignalCandidate(candidate) {
  const text = normalizeText(candidate?.text || "");
  if (!text) return false;
  if (!HIGH_SIGNAL_PROFANITY_PATTERN.test(text)) return false;

  const compactLength = text.replace(/\s+/g, "").length;
  const tokenCount = text.split(/\s+/).filter(Boolean).length;
  if (compactLength <= 12) {
    return true;
  }

  return compactLength <= 24 && tokenCount <= 3;
}

function shouldPreferStandaloneAnalysis(candidate) {
  if (!candidate) {
    return false;
  }

  if (candidate.candidateKind === "editable-value") {
    return true;
  }

  if (isGoogleSearchPage()) {
    if (
      candidate.element instanceof Element &&
      shouldAllowGoogleInteractiveElement(candidate.element) &&
      isShortHighSignalCandidate(candidate)
    ) {
      return true;
    }
    return false;
  }

  if (!isShortHighSignalCandidate(candidate)) {
    return false;
  }

  const element = candidate.element;
  if (!(element instanceof Element)) {
    return true;
  }

  if (isGoogleSearchPage()) {
    if (
      element.matches("h1, h2, h3, h4, [role='heading']") ||
      element.closest("h1, h2, h3, h4, [role='heading']")
    ) {
      return true;
    }

    if (
      element.matches("a[href]") ||
      element.closest("a[href]")
    ) {
      return true;
    }
  }

  if (
    element.closest(
      "#search .g, #search .tF2Cxc, #search .MjjYud, #search .yuRUbf, #search article, article, li, section, [role='article'], [data-hveid]"
    )
  ) {
    return false;
  }

  if (
    element.matches("a, h1, h2, h3, [role='heading']") ||
    element.closest("a, h1, h2, h3, [role='heading']")
  ) {
    return false;
  }

  return true;
}

function isForegroundMetadataCandidate(candidate) {
  const element = candidate?.element;
  if (!(element instanceof Element)) return false;
  if (element.tagName === "CITE") return true;
  if (element.tagName === "A" && !HIGH_SIGNAL_PROFANITY_PATTERN.test(candidate.text || "")) {
    return true;
  }
  if (element.closest("header, nav, [role='navigation'], [role='tablist'], [aria-label='탐색']")) {
    return true;
  }
  return false;
}

function isGoogleSearchPage() {
  return /(^|\.)google\./i.test(location.hostname || "") && location.pathname === "/search";
}

function isRapidlyChangingRealtimeHost() {
  const hostname = String(location.hostname || "").toLowerCase();
  return /(^|\.)google\./i.test(hostname) || /(^|\.)youtube\.com$/i.test(hostname);
}

function isGooglePriorityCandidate(candidate) {
  if (!isGoogleSearchPage()) return false;
  const element = candidate?.element;
  if (!(element instanceof Element)) return false;

  const inSearchSurface = element.closest("#search, main, [role='main']");
  if (!inSearchSurface) return false;

  if (candidate?.candidateKind === "editable-value") {
    return true;
  }

  if (element.matches("h3, [role='heading']") || element.closest("h3, [role='heading']")) {
    return true;
  }

  if (element.closest(".LC20lb, .DKV0Md, .yXK7lf")) {
    return true;
  }

  if (element.closest("a[href]")) {
    return true;
  }

  if (
    element.closest(".VwiC3b, .MUxGbd, [data-sncf], [data-snf], [data-content-feature='1']")
  ) {
    return true;
  }

  return false;
}

function isGoogleHeadingCandidate(candidate) {
  const element = candidate?.element;
  if (!(element instanceof Element)) {
    return false;
  }

  return Boolean(
    element.matches("h3, [role='heading']") ||
      element.closest("h3, [role='heading'], .LC20lb, .DKV0Md, .yXK7lf")
  );
}

function isGoogleSnippetCandidate(candidate) {
  const element = candidate?.element;
  if (!(element instanceof Element)) {
    return false;
  }

  return Boolean(
    element.closest(".VwiC3b, .MUxGbd, [data-sncf], [data-snf], [data-content-feature='1']")
  );
}

function selectGoogleForegroundCandidates(candidates) {
  if (!isGoogleSearchPage()) {
    return [];
  }

  const visibleContainers = getGoogleVisibleAnalysisContainers(MAX_HOT_PATH_CONTAINERS);
  if (visibleContainers.length === 0) {
    return [];
  }

  const containerOrder = new Map(
    visibleContainers.map((container, index) => [container, index])
  );

  const editableCandidates = collectEditableValueCandidates(1).filter((candidate) =>
    Array.isArray(candidates) && candidates.some((item) => item.nodeId === candidate.nodeId)
  );

  const selected = [];
  const selectedNodeIds = new Set();
  const perContainerCount = new Map();

  for (const candidate of editableCandidates) {
    selected.push(candidate);
    selectedNodeIds.add(candidate.nodeId);
  }

  const highSignalInteractiveCandidates = sortCandidatesByUrgency(
    candidates.filter(
      (candidate) =>
        candidate?.candidateKind !== "editable-value" &&
        candidate.element instanceof Element &&
        shouldAllowGoogleInteractiveElement(candidate.element) &&
        isShortHighSignalCandidate(candidate)
    ),
    buildRealtimeHints(cachedSettings)
  );

  for (const candidate of highSignalInteractiveCandidates) {
    if (selected.length >= MAX_FOREGROUND_WAVE_CANDIDATES) {
      break;
    }
    if (selectedNodeIds.has(candidate.nodeId)) {
      continue;
    }

    selected.push(candidate);
    selectedNodeIds.add(candidate.nodeId);
  }

  const orderedTextCandidates = [...candidates]
    .filter((candidate) => {
      if (!candidate || candidate.candidateKind === "editable-value") {
        return false;
      }

      const container = candidate.analysisContainer;
      return containerOrder.has(container) && !isForegroundMetadataCandidate(candidate);
    })
    .sort((left, right) => {
      const leftContainerOrder = containerOrder.get(left.analysisContainer) ?? Number.MAX_SAFE_INTEGER;
      const rightContainerOrder = containerOrder.get(right.analysisContainer) ?? Number.MAX_SAFE_INTEGER;
      if (leftContainerOrder !== rightContainerOrder) {
        return leftContainerOrder - rightContainerOrder;
      }
      if (left.distanceFromViewport !== right.distanceFromViewport) {
        return left.distanceFromViewport - right.distanceFromViewport;
      }
      return left.top - right.top;
    });

  for (const candidate of orderedTextCandidates) {
    if (selectedNodeIds.has(candidate.nodeId)) continue;

    const container = candidate.analysisContainer;
    const count = perContainerCount.get(container) || 0;
    if (count >= MAX_GOOGLE_CANDIDATES_PER_CONTAINER) {
      continue;
    }

    perContainerCount.set(container, count + 1);
    selectedNodeIds.add(candidate.nodeId);
    selected.push(candidate);
  }

  return selected;
}

function selectCandidatesForRun(candidates, settings, runReason) {
  const hints = buildRealtimeHints(settings);
  const sortedCandidates = sortCandidatesByUrgency(candidates, hints);

  if (!isBroadAnalysisReason(runReason) && isGoogleSearchPage()) {
    const prioritized = [];
    const selectedNodeIds = new Set();

    for (const candidate of selectGoogleForegroundCandidates(candidates)) {
      if (!candidate || selectedNodeIds.has(candidate.nodeId)) {
        continue;
      }
      selectedNodeIds.add(candidate.nodeId);
      prioritized.push(candidate);
    }

    for (const candidate of sortedCandidates) {
      if (!candidate || selectedNodeIds.has(candidate.nodeId)) {
        continue;
      }

      selectedNodeIds.add(candidate.nodeId);
      prioritized.push(candidate);

      if (prioritized.length >= MAX_BACKGROUND_CANDIDATES) {
        break;
      }
    }

    if (prioritized.length > 0) {
      return prioritized;
    }
  }

  if (isBroadAnalysisReason(runReason)) {
    return sortedCandidates.slice(0, MAX_BACKGROUND_CANDIDATES);
  }

  const domainPriorityCandidates = sortedCandidates.filter(
    (candidate) =>
      isGooglePriorityCandidate(candidate) &&
      getCandidateUrgency(candidate, hints) >= 6
  );

  const standalonePriorityCandidates = sortedCandidates.filter(
    (candidate) =>
      shouldPreferStandaloneAnalysis(candidate) &&
      getCandidateUrgency(candidate, hints) >= 8
  );
  const selectedNodeIds = new Set(
    [
      ...domainPriorityCandidates.slice(0, MAX_DOMAIN_PRIORITY_CANDIDATES),
      ...standalonePriorityCandidates.slice(0, MAX_FOREGROUND_CANDIDATES)
    ]
      .filter(Boolean)
      .map((candidate) => candidate.nodeId)
  );

  const suspiciousCandidates = sortedCandidates.filter(
    (candidate) =>
      !selectedNodeIds.has(candidate.nodeId) &&
      getCandidateUrgency(candidate, hints) >= 8 &&
      !isForegroundMetadataCandidate(candidate)
  );

  const mergedCandidates = [
    ...domainPriorityCandidates.slice(0, MAX_DOMAIN_PRIORITY_CANDIDATES),
    ...standalonePriorityCandidates.slice(0, MAX_FOREGROUND_CANDIDATES),
    ...suspiciousCandidates
  ];

  if (mergedCandidates.length > 0) {
    return mergedCandidates.slice(
      0,
      Math.max(MAX_FOREGROUND_CANDIDATES + MAX_DOMAIN_PRIORITY_CANDIDATES, 16)
    );
  }

  return [];
}

function buildForegroundAnalysisUnits(candidates) {
  if (!Array.isArray(candidates) || candidates.length === 0) {
    return [];
  }

  return candidates.map((candidate) => ({
    cacheScope:
      candidate?.candidateKind === "editable-value"
        ? "foreground-editable"
        : "foreground-standalone",
    cacheKey: normalizeText(candidate.text),
    members: [
      {
        candidate,
        start: 0,
        end: candidate.text.length
      }
    ],
    text: candidate.text
  }));
}

function selectForegroundWaveCandidates(candidates, settings, runReason) {
  const nextCandidates = (Array.isArray(candidates) ? candidates : []).filter(Boolean);
  if (nextCandidates.length === 0) {
    return [];
  }

  if (
    runReason === "input" ||
    runReason === "input-hot-path" ||
    runReason === "initial-editable-pass"
  ) {
    return nextCandidates.slice(0, 1);
  }

  if (runReason === "background-validation") {
    const hints = buildRealtimeHints(settings);
    const backgroundCandidates = sortCandidatesByUrgency(
      nextCandidates.filter((candidate) => !isForegroundMetadataCandidate(candidate)),
      hints
    );

    if (isGoogleSearchPage()) {
      return selectForegroundCandidatesByContainer(
        backgroundCandidates,
        MAX_BACKGROUND_CONTAINERS
      ).slice(0, MAX_BACKGROUND_CANDIDATES);
    }

    return backgroundCandidates.slice(0, MAX_BACKGROUND_CANDIDATES);
  }

  if (isGoogleSearchPage()) {
    const editableCandidates = collectEditableValueCandidates(1).filter((candidate) =>
      nextCandidates.some((item) => item.nodeId === candidate.nodeId)
    );
    const textCandidates = selectGoogleForegroundCandidates(nextCandidates).filter(
      (candidate) => candidate.candidateKind !== "editable-value"
    );
    const selected = [...editableCandidates];
    const selectedNodeIds = new Set(selected.map((candidate) => candidate.nodeId));
    const visibleContainers = getGoogleVisibleAnalysisContainers(MAX_FOREGROUND_WAVE_CONTAINERS);
    const candidatesByContainer = new Map();

    for (const candidate of textCandidates) {
      const container = candidate.analysisContainer;
      if (!container) continue;
      if (!candidatesByContainer.has(container)) {
        candidatesByContainer.set(container, []);
      }
      candidatesByContainer.get(container).push(candidate);
    }

    const firstContainer = visibleContainers[0] || null;
    const firstContainerCandidates = firstContainer
      ? (candidatesByContainer.get(firstContainer) || [])
      : [];
    const firstTitleCandidate =
      firstContainerCandidates.find((candidate) => isGoogleHeadingCandidate(candidate)) ||
      firstContainerCandidates[0] ||
      null;
    if (firstTitleCandidate && !selectedNodeIds.has(firstTitleCandidate.nodeId)) {
      selected.push(firstTitleCandidate);
      selectedNodeIds.add(firstTitleCandidate.nodeId);
    }

    const firstSnippetCandidate = firstContainerCandidates.find(
      (candidate) =>
        !selectedNodeIds.has(candidate.nodeId) &&
        !isForegroundMetadataCandidate(candidate) &&
        isGoogleSnippetCandidate(candidate)
    );
    if (firstSnippetCandidate && !selectedNodeIds.has(firstSnippetCandidate.nodeId)) {
      selected.push(firstSnippetCandidate);
      selectedNodeIds.add(firstSnippetCandidate.nodeId);
    }

    for (const container of visibleContainers.slice(1)) {
      if (selected.length >= MAX_FOREGROUND_WAVE_CANDIDATES) {
        break;
      }

      const containerCandidates = candidatesByContainer.get(container) || [];
      const nextCandidate =
        containerCandidates.find(
          (candidate) =>
            !selectedNodeIds.has(candidate.nodeId) &&
            isGoogleHeadingCandidate(candidate)
        ) ||
        containerCandidates.find(
        (candidate) =>
          !selectedNodeIds.has(candidate.nodeId) &&
          !isForegroundMetadataCandidate(candidate)
        );
      if (!nextCandidate) {
        continue;
      }

      selected.push(nextCandidate);
      selectedNodeIds.add(nextCandidate.nodeId);
    }

    if (selected.length < MAX_FOREGROUND_WAVE_CANDIDATES) {
      for (const container of visibleContainers.slice(1)) {
        if (selected.length >= MAX_FOREGROUND_WAVE_CANDIDATES) {
          break;
        }

        const containerCandidates = candidatesByContainer.get(container) || [];
        const snippetCandidate = containerCandidates.find(
          (candidate) =>
            !selectedNodeIds.has(candidate.nodeId) &&
            !isForegroundMetadataCandidate(candidate) &&
            isGoogleSnippetCandidate(candidate)
        );
        if (!snippetCandidate) {
          continue;
        }

        selected.push(snippetCandidate);
        selectedNodeIds.add(snippetCandidate.nodeId);
      }
    }

    return selected.slice(0, MAX_FOREGROUND_WAVE_CANDIDATES);
  }

  const hints = buildRealtimeHints(settings);
  const editableCandidates = sortCandidatesByUrgency(
    nextCandidates.filter((candidate) => candidate.candidateKind === "editable-value"),
    hints
  ).slice(0, 1);
  const textCandidates = sortCandidatesByUrgency(
    nextCandidates.filter(
      (candidate) =>
        candidate.candidateKind !== "editable-value" &&
        !isForegroundMetadataCandidate(candidate)
    ),
    hints
  );

  return [...editableCandidates, ...textCandidates].slice(0, MAX_FOREGROUND_WAVE_CANDIDATES);
}

function markCandidatesAnalysisGeneration(candidates, generation) {
  for (const candidate of Array.isArray(candidates) ? candidates : []) {
    if (candidate?.state) {
      candidate.state.analysisGeneration = generation;
    }
  }
}

function getDecisionStageRank(stage) {
  return Number(DECISION_STAGE_RANK[String(stage || "")] || 0);
}

function isCandidateGenerationCurrent(candidate, generation) {
  if (!candidate?.state) {
    return false;
  }

  if (Number(generation || 0) > 0 &&
      Number(candidate.state.analysisGeneration || 0) !== Number(generation)) {
    return false;
  }

  if (candidate.candidateKind === "editable-value") {
    return Boolean(candidate.element?.isConnected) &&
      buildFingerprint(normalizeText(candidate.element.value || "")) === candidate.fingerprint;
  }

  return Boolean(candidate.textNode?.isConnected) &&
    buildFingerprint(normalizeText(getSourceText(candidate.state))) === candidate.fingerprint;
}

function shouldSkipCandidateApply(candidate, state, stage) {
  if (!candidate?.fingerprint || !state?.lastAppliedFingerprint) {
    return false;
  }

  if (String(state.lastAppliedFingerprint) !== String(candidate.fingerprint)) {
    return false;
  }

  return getDecisionStageRank(stage) < getDecisionStageRank(state.lastAppliedStage);
}

function markCandidateApplied(candidate, stage, blocked) {
  if (!candidate?.state) {
    return;
  }

  candidate.state.lastAppliedFingerprint = String(candidate.fingerprint || "");
  candidate.state.lastAppliedStage = String(stage || "");
  candidate.state.lastAppliedBlocked = Boolean(blocked);

  if (String(stage || "") === "reconcile") {
    candidate.state.lastReconcileFingerprint = String(candidate.fingerprint || "");
  }
}

function buildSyntheticTextCandidate(state, element, text) {
  const sourceText = String(text || "");
  return {
    nodeId: state.nodeId,
    textNode: state.textNode,
    state,
    element,
    text: sourceText,
    normalizedText: normalizeText(sourceText).toLowerCase(),
    analysisContainer: getAnalysisContainer(element) || element,
    packageName: `web::${location.hostname || "unknown"}`,
    className:
      typeof element?.className === "string" && element.className.trim()
        ? element.className.trim()
        : element?.tagName || "TEXT",
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    distanceFromViewport: 0,
    fingerprint: buildFingerprint(normalizeText(sourceText))
  };
}

function isGoogleMaskTargetElement(element) {
  if (!(element instanceof Element)) {
    return false;
  }

  if (element.matches("cite, [role='navigation'], nav")) {
    return false;
  }

  if (element.closest("cite, [role='navigation'], nav")) {
    return false;
  }

  if (element.matches("h1, h2, h3, h4, [role='heading']")) {
    return true;
  }

  if (element.closest("h1, h2, h3, h4, [role='heading']")) {
    return true;
  }

  if (element.closest(".VwiC3b, .MUxGbd, [data-sncf], [data-snf], [data-content-feature='1']")) {
    return true;
  }

  if (shouldAllowGoogleInteractiveElement(element)) {
    return true;
  }

  if (element.closest("[data-attrid], .kno-rdesc, .IZ6rdc")) {
    return true;
  }

  return false;
}

function shouldCreateContainerMember(segmentElement, normalizedSegment, selectedCandidate) {
  if (selectedCandidate) {
    return true;
  }

  if (!isCandidateTextUseful(normalizedSegment, segmentElement)) {
    return false;
  }

  if (!isGoogleSearchPage()) {
    return false;
  }

  return isGoogleMaskTargetElement(segmentElement);
}

function collectUnitCandidates(analysisUnits) {
  const candidatesByNodeId = new Map();

  for (const unit of Array.isArray(analysisUnits) ? analysisUnits : []) {
    for (const member of Array.isArray(unit?.members) ? unit.members : []) {
      const candidate = member?.candidate;
      if (!candidate?.nodeId) continue;
      if (!candidatesByNodeId.has(candidate.nodeId)) {
        candidatesByNodeId.set(candidate.nodeId, candidate);
      }
    }
  }

  return [...candidatesByNodeId.values()];
}

function boundAnalysisUnitForHotPath(unit) {
  if (!unit?.text || !Array.isArray(unit.members) || unit.members.length === 0) {
    return unit;
  }

  const sourceText = String(unit.text || "");
  if (sourceText.length <= MAX_HOT_PATH_CONTEXT_LENGTH) {
    return unit;
  }

  const memberStart = Math.min(...unit.members.map((member) => Number(member.start || 0)));
  const memberEnd = Math.max(...unit.members.map((member) => Number(member.end || 0)));
  let sliceStart = Math.max(0, memberStart - 36);
  let sliceEnd = Math.min(sourceText.length, memberEnd + 72);

  if ((sliceEnd - sliceStart) > MAX_HOT_PATH_CONTEXT_LENGTH) {
    sliceEnd = Math.min(sourceText.length, sliceStart + MAX_HOT_PATH_CONTEXT_LENGTH);
    if (memberEnd > sliceEnd) {
      sliceStart = Math.max(0, memberEnd - MAX_HOT_PATH_CONTEXT_LENGTH);
      sliceEnd = Math.min(sourceText.length, sliceStart + MAX_HOT_PATH_CONTEXT_LENGTH);
    }
  }

  return {
    ...unit,
    cacheKey: normalizeText(sourceText.slice(sliceStart, sliceEnd)),
    text: sourceText.slice(sliceStart, sliceEnd),
    members: unit.members
      .map((member) => ({
        ...member,
        start: Number(member.start || 0) - sliceStart,
        end: Number(member.end || 0) - sliceStart
      }))
      .filter((member) => member.end > member.start)
  };
}

function boundAnalysisUnitForReconcile(unit) {
  if (!unit?.text || !Array.isArray(unit.members) || unit.members.length === 0) {
    return unit;
  }

  const sourceText = String(unit.text || "");
  if (sourceText.length <= MAX_RECONCILE_CONTEXT_LENGTH) {
    return unit;
  }

  const memberStart = Math.min(...unit.members.map((member) => Number(member.start || 0)));
  const memberEnd = Math.max(...unit.members.map((member) => Number(member.end || 0)));
  let sliceStart = Math.max(0, memberStart - 96);
  let sliceEnd = Math.min(sourceText.length, memberEnd + 192);

  if ((sliceEnd - sliceStart) > MAX_RECONCILE_CONTEXT_LENGTH) {
    sliceEnd = Math.min(sourceText.length, sliceStart + MAX_RECONCILE_CONTEXT_LENGTH);
    if (memberEnd > sliceEnd) {
      sliceStart = Math.max(0, memberEnd - MAX_RECONCILE_CONTEXT_LENGTH);
      sliceEnd = Math.min(sourceText.length, sliceStart + MAX_RECONCILE_CONTEXT_LENGTH);
    }
  }

  return {
    ...unit,
    cacheKey: normalizeText(sourceText.slice(sliceStart, sliceEnd)),
    text: sourceText.slice(sliceStart, sliceEnd),
    members: unit.members
      .map((member) => ({
        ...member,
        start: Number(member.start || 0) - sliceStart,
        end: Number(member.end || 0) - sliceStart
      }))
      .filter((member) => member.end > member.start)
  };
}

function buildHotPathAnalysisUnits(candidates, options = {}) {
  if (!Array.isArray(candidates) || candidates.length === 0) {
    return [];
  }

  const editableCandidates = [];
  const textCandidates = [];

  for (const candidate of candidates) {
    if (candidate?.candidateKind === "editable-value") {
      editableCandidates.push(candidate);
    } else {
      textCandidates.push(candidate);
    }
  }

  const units = [];

  if (editableCandidates.length > 0) {
    units.push(...buildForegroundAnalysisUnits(editableCandidates));
  }

  if (textCandidates.length > 0) {
    if (isGoogleSearchPage()) {
      const preferStandaloneGoogle = options.preferStandaloneGoogle !== false;
      if (preferStandaloneGoogle) {
        units.push(...buildForegroundAnalysisUnits(textCandidates));
      } else {
        const containerLimit = Math.max(
          1,
          Number.isFinite(options.containerLimit)
            ? Number(options.containerLimit)
            : MAX_FOREGROUND_WAVE_CONTAINERS
        );
        const contextualUnits = buildContainerAnalysisUnits(
          selectForegroundCandidatesByContainer(textCandidates, containerLimit)
        )
          .map((unit) => ({
            ...unit,
            cacheScope: "foreground-contextual",
            cacheKey: normalizeText(unit?.text || "")
          }))
          .map((unit) => (options.boundContext ? boundAnalysisUnitForHotPath(unit) : unit))
          .filter((unit) => unit?.text && Array.isArray(unit.members) && unit.members.length > 0);

        units.push(...contextualUnits);
      }
    } else {
      units.push(...buildForegroundAnalysisUnits(textCandidates));
    }
  }

  return units;
}

function buildContainerAnalysisUnits(candidates) {
  if (!Array.isArray(candidates) || candidates.length === 0) {
    return [];
  }

  const standaloneCandidates = candidates.filter((candidate) =>
    shouldPreferStandaloneAnalysis(candidate)
  );
  const contextualCandidates = candidates.filter(
    (candidate) => !shouldPreferStandaloneAnalysis(candidate)
  );

  const units = [];
  if (standaloneCandidates.length > 0) {
    units.push(...buildForegroundAnalysisUnits(standaloneCandidates));
  }

  if (contextualCandidates.length === 0) {
    return units;
  }

  const groupedCandidates = new Map();

  for (const candidate of contextualCandidates) {
    const container = candidate.analysisContainer || getAnalysisContainer(candidate.element) || candidate.element;
    if (!container) continue;
    const key = container;
    if (!groupedCandidates.has(key)) {
      groupedCandidates.set(key, []);
    }
    groupedCandidates.get(key).push(candidate);
  }

  for (const [container, containerCandidates] of groupedCandidates.entries()) {
    const selectedByNodeId = new Map(
      containerCandidates.map((candidate) => [candidate.nodeId, candidate])
    );
    const memberByNodeId = new Map();
    const members = [];
    let text = "";
    let offset = 0;

    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        if (!(node instanceof Text)) return NodeFilter.FILTER_REJECT;
        if (!node.parentElement) return NodeFilter.FILTER_REJECT;
        if (shouldSkipTextNodeParent(node.parentElement)) {
          return NodeFilter.FILTER_REJECT;
        }
        return NodeFilter.FILTER_ACCEPT;
      }
    });

    while (walker.nextNode()) {
      const textNode = walker.currentNode;
      const state = registerTextNode(textNode);
      if (!state) continue;

      const segmentElement = getRenderableParent(textNode);
      const segmentText = getSourceText(state);
      const normalizedSegment = normalizeText(segmentText);
      const selectedCandidate = selectedByNodeId.get(state.nodeId);

      if (!normalizedSegment) continue;
      if (!shouldCreateContainerMember(segmentElement, normalizedSegment, selectedCandidate)) {
        continue;
      }

      if (text.length > 0) {
        text += "\n";
        offset += 1;
      }

      const start = offset;
      text += segmentText;
      offset += segmentText.length;

      if (!selectedCandidate) {
        continue;
      }

      if (!memberByNodeId.has(selectedCandidate.nodeId)) {
        const member = {
          candidate: selectedCandidate,
          start,
          end: offset
        };
        memberByNodeId.set(selectedCandidate.nodeId, member);
        members.push(member);
      }
    }

    if (!text.trim() || members.length === 0) {
      for (const candidate of containerCandidates) {
        units.push({
          text: candidate.text,
          members: [
            {
              candidate,
              start: 0,
              end: candidate.text.length
            }
          ]
        });
      }
      continue;
    }

    const coveredNodeIds = new Set(members.map((member) => member.candidate.nodeId));
    units.push({ cacheKey: normalizeText(text), text, members });

    for (const candidate of containerCandidates) {
      if (coveredNodeIds.has(candidate.nodeId)) continue;
      units.push({
        cacheKey: normalizeText(candidate.text),
        text: candidate.text,
        members: [
          {
            candidate,
            start: 0,
            end: candidate.text.length
          }
        ]
      });
    }
  }

  return units;
}

function buildContextualAnalysisUnits(candidates) {
  if (!Array.isArray(candidates) || candidates.length === 0) {
    return [];
  }

  const groupedCandidates = new Map();
  for (const candidate of candidates) {
    if (!candidate || candidate.candidateKind === "editable-value") {
      continue;
    }

    const container =
      candidate.analysisContainer || getAnalysisContainer(candidate.element) || candidate.element;
    if (!container) continue;

    if (!groupedCandidates.has(container)) {
      groupedCandidates.set(container, []);
    }
    groupedCandidates.get(container).push(candidate);
  }

  const units = [];
  for (const [container, containerCandidates] of groupedCandidates.entries()) {
    const selectedByNodeId = new Map(
      containerCandidates.map((candidate) => [candidate.nodeId, candidate])
    );
    const memberByNodeId = new Map();
    const members = [];
    let text = "";
    let offset = 0;

    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        if (!(node instanceof Text)) return NodeFilter.FILTER_REJECT;
        if (!node.parentElement) return NodeFilter.FILTER_REJECT;
        if (shouldSkipTextNodeParent(node.parentElement)) {
          return NodeFilter.FILTER_REJECT;
        }
        return NodeFilter.FILTER_ACCEPT;
      }
    });

    while (walker.nextNode()) {
      const textNode = walker.currentNode;
      const state = registerTextNode(textNode);
      if (!state) continue;

      const segmentElement = getRenderableParent(textNode);
      const segmentText = getSourceText(state);
      const normalizedSegment = normalizeText(segmentText);
      const selectedCandidate = selectedByNodeId.get(state.nodeId);

      if (!normalizedSegment) continue;
      const shouldIncludeSegment =
        Boolean(selectedCandidate) ||
        isCandidateTextUseful(normalizedSegment, segmentElement) ||
        (isGoogleSearchPage() && isGoogleMaskTargetElement(segmentElement));

      if (!shouldIncludeSegment) {
        continue;
      }

      if (text.length > 0) {
        text += "\n";
        offset += 1;
      }

      const start = offset;
      text += segmentText;
      offset += segmentText.length;

      if (!selectedCandidate) {
        continue;
      }

      if (!memberByNodeId.has(selectedCandidate.nodeId)) {
        const member = {
          candidate: selectedCandidate,
          start,
          end: offset
        };
        memberByNodeId.set(selectedCandidate.nodeId, member);
        members.push(member);
      }
    }

    if (!text.trim() || members.length === 0) {
      for (const candidate of containerCandidates) {
        units.push({
          cacheScope: "reconcile-fallback",
          cacheKey: normalizeText(candidate.text),
          text: candidate.text,
          members: [
            {
              candidate,
              start: 0,
              end: candidate.text.length
            }
          ]
        });
      }
      continue;
    }

    units.push({
      cacheScope: "reconcile-contextual",
      cacheKey: normalizeText(text),
      text,
      members
    });
  }

  return units
    .map((unit) => boundAnalysisUnitForReconcile(unit))
    .filter((unit) => unit?.text && Array.isArray(unit.members) && unit.members.length > 0);
}

function emptyCategoryHits() {
  return {
    abuse: 0,
    hate: 0,
    insult: 0,
    spam: 0,
    custom: 0
  };
}

function getAnalysisCacheKey(entry) {
  const scope = normalizeLabel(entry?.cacheScope || "default");
  const sensitivity = normalizeSensitivity(
    entry?.cacheSensitivity ?? cachedSettings?.sensitivity ?? DEFAULT_SETTINGS.sensitivity
  );
  const textKey = normalizeText(entry?.cacheKey || entry?.text || "");
  return `${scope}::${sensitivity}::${textKey}`;
}

function getCachedAnalysis(entry) {
  const key = getAnalysisCacheKey(entry);
  if (!ANALYSIS_CACHE.has(key)) return null;

  const cached = ANALYSIS_CACHE.get(key);
  if (!cached || typeof cached !== "object") {
    ANALYSIS_CACHE.delete(key);
    return null;
  }
  if (Number(cached.expiresAt || 0) <= Date.now()) {
    ANALYSIS_CACHE.delete(key);
    return null;
  }
  ANALYSIS_CACHE.delete(key);
  ANALYSIS_CACHE.set(key, cached);
  return cached.value;
}

function shouldReuseCachedAnalysis(entry, cachedValue) {
  if (!cachedValue || typeof cachedValue !== "object") {
    return false;
  }

  if (cachedValue.__shieldtextSkipped === true) {
    return false;
  }

  if (cachedValue.is_offensive) {
    return true;
  }

  const scope = normalizeLabel(entry?.cacheScope || "");
  if (
    !cachedValue.is_offensive &&
    isRapidlyChangingRealtimeHost() &&
    (scope === "reconcile-contextual" || scope === "reconcile-fallback")
  ) {
    return false;
  }

  if (scope === "reconcile-contextual" || scope === "reconcile-fallback") {
    return true;
  }

  const text = normalizeText(entry?.text || entry?.cacheKey || "");
  if (!text) {
    return false;
  }

  if (HIGH_SIGNAL_PROFANITY_PATTERN.test(text)) {
    return false;
  }

  return scope === "foreground-editable";
}

function shouldCacheAnalysisResult(value) {
  if (!value || typeof value !== "object") {
    return false;
  }

  if (value.__shieldtextSkipped === true) {
    return false;
  }

  const hasExpectedShape = Boolean(
    "is_offensive" in value &&
    "is_profane" in value &&
    "is_toxic" in value &&
    "is_hate" in value
  );
  if (!hasExpectedShape) {
    return false;
  }

  const sourceText = String(value.original || value.text || "");
  if (value.is_offensive && normalizeEvidenceSpans(value.evidence_spans, sourceText).length === 0) {
    return false;
  }

  return true;
}

function getAnalysisCacheTtlMs(entry, value) {
  if (!value || typeof value !== "object") {
    return 0;
  }

  if (value.is_offensive) {
    return OFFENSIVE_CACHE_TTL_MS;
  }

  const scope = normalizeLabel(entry?.cacheScope || "");
  if (scope === "foreground-standalone") {
    return 0;
  }
  if (scope === "foreground-editable") {
    return 350;
  }
  if (scope === "foreground-contextual") {
    return 0;
  }
  if (scope === "reconcile-contextual") {
    return RECONCILE_CONTEXTUAL_SAFE_CACHE_TTL_MS;
  }
  return FOREGROUND_STANDALONE_SAFE_CACHE_TTL_MS;
}

function setCachedAnalysis(entry, value) {
  const key = getAnalysisCacheKey(entry);
  if (!key) return;

  if (!shouldCacheAnalysisResult(value)) {
    ANALYSIS_CACHE.delete(key);
    return;
  }

  const ttlMs = getAnalysisCacheTtlMs(entry, value);
  if (ttlMs <= 0) {
    ANALYSIS_CACHE.delete(key);
    return;
  }

  if (ANALYSIS_CACHE.has(key)) {
    ANALYSIS_CACHE.delete(key);
  }

  ANALYSIS_CACHE.set(key, {
    value,
    expiresAt: Date.now() + ttlMs
  });

  while (ANALYSIS_CACHE.size > ANALYSIS_CACHE_LIMIT) {
    const oldestKey = ANALYSIS_CACHE.keys().next().value;
    ANALYSIS_CACHE.delete(oldestKey);
  }
}

function chunkArray(items, chunkSize) {
  const chunks = [];
  for (let index = 0; index < items.length; index += chunkSize) {
    chunks.push(items.slice(index, index + chunkSize));
  }
  return chunks;
}

function getBackendRequestBatchSize(analysisMode) {
  const mode = String(analysisMode || "");
  if (mode === "reconcile" || mode === "background-validation") {
    return RECONCILE_BACKEND_BATCH_SIZE;
  }
  return FOREGROUND_BACKEND_BATCH_SIZE;
}

function normalizeEvidenceSpans(spans, originalText) {
  const sourceText = String(originalText || "");
  const nextSpans = (Array.isArray(spans) ? spans : [])
    .map((span) => ({
      start: Math.max(0, Number(span?.start ?? 0)),
      end: Math.min(sourceText.length, Number(span?.end ?? 0)),
      score: Number(span?.score ?? 0),
      text: String(span?.text || "")
    }))
    .filter((span) => Number.isFinite(span.start) && Number.isFinite(span.end) && span.end > span.start)
    .sort((left, right) => left.start - right.start || left.end - right.end);

  const merged = [];
  for (const span of nextSpans) {
    const previous = merged[merged.length - 1];
    if (previous && span.start <= previous.end) {
      previous.end = Math.max(previous.end, span.end);
      previous.score = Math.max(previous.score, span.score);
      previous.text = sourceText.slice(previous.start, previous.end);
      continue;
    }

    merged.push({
      ...span,
      text: sourceText.slice(span.start, span.end)
    });
  }

  return merged;
}

function expandMaskSpansForDisplay(text, spans) {
  const sourceText = String(text || "");
  if (!sourceText) {
    return [];
  }

  const expanded = normalizeEvidenceSpans(spans, sourceText).map((span) => {
    let nextEnd = span.end;

    if (/[가-힣]/.test(sourceText.slice(Math.max(0, span.end - 1), span.end))) {
      for (const suffix of KOREAN_MASK_SUFFIXES) {
        if (sourceText.startsWith(suffix, nextEnd)) {
          nextEnd += suffix.length;
          break;
        }
      }
    }

    return {
      ...span,
      end: Math.min(sourceText.length, nextEnd),
      text: sourceText.slice(span.start, Math.min(sourceText.length, nextEnd))
    };
  });

  return normalizeEvidenceSpans(expanded, sourceText);
}

function getForegroundBackendSource(meta) {
  const requestedCount = Number(meta?.requestedCount || 0);
  const contentCacheHitCount = Number(meta?.cacheHitCount || 0);
  const backendCacheHitCount = Number(meta?.backendCacheHitCount || 0);

  if (requestedCount > 0) {
    return "live-backend";
  }
  if (backendCacheHitCount > 0 && contentCacheHitCount > 0) {
    return "mixed-cache";
  }
  if (backendCacheHitCount > 0) {
    return "service-worker-cache";
  }
  if (contentCacheHitCount > 0) {
    return "content-cache";
  }
  return "fallback-none";
}

async function analyzePayloadWithRealtimeWorker(analysisUnits, settings, onProgress, options = {}) {
  const startedAt = performance.now();
  const suppressHotPathFailure = options.suppressHotPathFailure === true;

  try {
    const response = await analyzePayloadWithBackend(analysisUnits, onProgress, options);
    if (!response?.ok) {
      const failure = {
        reason: String(response?.error?.reason || "FOREGROUND_BACKEND_FAILED"),
        errorCode: String(response?.error?.errorCode || "FOREGROUND_BACKEND_FAILED"),
        retryable: Boolean(response?.error?.retryable)
      };

      if (!suppressHotPathFailure) {
        markRealtimeWorkerFailure(
          Object.assign(new Error(failure.reason), failure),
          {
            errorCode: failure.errorCode,
            phase: "foreground-backend",
            strategy: "backend-first"
          }
        );
      }

      return {
        ok: false,
        error: failure,
        apiBaseUrl: response?.apiBaseUrl || settings?.backendApiBaseUrl || "",
        backendStatus: response?.error?.backendStatus || "degraded",
        requestCount: Number(response?.requestCount || 0),
        splitRetryCount: Number(response?.splitRetryCount || 0),
        skippedChunkCount: Number(response?.skippedChunkCount || 0),
        failedTextCount: Number(response?.failedTextCount || 0),
        chunkSize: Number(response?.chunkSize || 0),
        requestTimeoutMs: Number(response?.requestTimeoutMs || 0),
        lastBackendErrorCode: String(response?.lastBackendErrorCode || ""),
        backendRequestTimings: Array.isArray(response?.backendRequestTimings)
          ? response.backendRequestTimings
          : []
      };
    }

    if (!suppressHotPathFailure) {
      setRealtimeWorkerStatus("ready", {
        failure: null,
        initLatencyMs: 0,
        strategy: "backend-first"
      });
    }

    return {
      ok: true,
      cacheHitCount: Number(response?.cacheHitCount || 0),
      backendCacheHitCount: Number(response?.backendCacheHitCount || 0),
      requestCount: Number(response?.requestCount || 0),
      splitRetryCount: Number(response?.splitRetryCount || 0),
      skippedChunkCount: Number(response?.skippedChunkCount || 0),
      failedTextCount: Number(response?.failedTextCount || 0),
      chunkSize: Number(response?.chunkSize || 0),
      requestTimeoutMs: Number(response?.requestTimeoutMs || 0),
      lastBackendErrorCode: String(response?.lastBackendErrorCode || ""),
      backendRequestTimings: Array.isArray(response?.backendRequestTimings)
        ? response.backendRequestTimings
        : [],
      durationMs: Math.max(
        Number(response?.backendDurationMs || 0),
        Math.round(performance.now() - startedAt)
      ),
      strategy: "backend-first",
      apiBaseUrl: response?.apiBaseUrl || settings?.backendApiBaseUrl || "",
      backendStatus: response?.backendStatus || "ready",
      requestedCount: Number(response?.requestedCount || 0),
      foregroundBackendSource: getForegroundBackendSource(response),
      results: Array.isArray(response?.results) ? response.results : []
    };
  } catch (error) {
    const invalidated = handleExtensionContextError(error);
    const failure = {
      reason: String(error?.message || error || "FOREGROUND_BACKEND_FAILED"),
      errorCode: invalidated
        ? "EXTENSION_CONTEXT_INVALIDATED"
        : String(error?.errorCode || "FOREGROUND_BACKEND_FAILED"),
      retryable: !invalidated
    };

    if (!invalidated && !suppressHotPathFailure) {
      markRealtimeWorkerFailure(
        Object.assign(new Error(failure.reason), failure),
        {
          errorCode: failure.errorCode,
          phase: error?.phase || "foreground-backend",
          strategy: "backend-first"
        }
      );
    }

    return {
      ok: false,
      error: failure,
      apiBaseUrl: settings?.backendApiBaseUrl || "",
      backendStatus: "degraded"
    };
  }
}

function scheduleHotPathStatsPersist(partialStats) {
  pendingHotPathStats = {
    ...(pendingHotPathStats || {}),
    ...(partialStats || {})
  };

  if (hotPathStatsPersistTimerId) {
    window.clearTimeout(hotPathStatsPersistTimerId);
  }

  hotPathStatsPersistTimerId = window.setTimeout(async () => {
    hotPathStatsPersistTimerId = null;
    const statsPatch = pendingHotPathStats;
    pendingHotPathStats = null;
    if (!statsPatch) return;

    try {
      const currentState = await safeStorageLocalGet(["lastStats"]);
      await safeStorageLocalSet({
        lastRunAt: Date.now(),
        lastPipelineError: null,
        lastStats: {
          ...(currentState.lastStats || {}),
          ...getRealtimeWorkerDiagnostics(),
          ...statsPatch
        }
      });
    } catch (error) {
      console.error("[청마루] hot path stats persist failed", error);
    }
  }, 140);
}

async function analyzePayloadWithBackend(items, onProgress, options = {}) {
  const cacheSensitivity = normalizeSensitivity(
    options.sensitivity ?? cachedSettings?.sensitivity ?? DEFAULT_SETTINGS.sensitivity
  );
  const resultsByText = new Map();
  const pendingRequests = [];
  const pendingRequestKeys = new Set();
  const itemsByText = new Map();
  let cacheHitCount = 0;
  let backendCacheHitCount = 0;
  const requestTimeoutMs = Math.max(150, Number(options.requestTimeoutMs || 0) || 0);

  for (const item of items) {
    const cacheEntry = {
      ...item,
      cacheSensitivity
    };
    const key = getAnalysisCacheKey(cacheEntry);
    if (!itemsByText.has(key)) {
      itemsByText.set(key, []);
    }
    itemsByText.get(key).push(cacheEntry);

    const cached = getCachedAnalysis(cacheEntry);

    if (shouldReuseCachedAnalysis(cacheEntry, cached)) {
      resultsByText.set(key, cached);
      cacheHitCount += 1;
      continue;
    }

    if (!pendingRequestKeys.has(key)) {
      pendingRequestKeys.add(key);
      pendingRequests.push({
        key,
        entry: cacheEntry,
        text: item.text
      });
    }
  }

  let backendDurationMs = 0;
  let apiBaseUrl = "";
  let backendStatus = "ready";
  let serviceWorkerRequestCount = 0;
  let serviceWorkerSplitRetryCount = 0;
  let serviceWorkerSkippedChunkCount = 0;
  let serviceWorkerFailedTextCount = 0;
  let serviceWorkerChunkSize = 0;
  let serviceWorkerLastBackendErrorCode = "";
  let serviceWorkerRequestTimeoutMs = 0;
  const backendRequestTimings = [];
  const requestBatchSize = Math.max(
    1,
    getBackendRequestBatchSize(String(options.analysisMode || ""))
  );
  const requestBatches = pendingRequests.length > 0
    ? chunkArray(pendingRequests, requestBatchSize)
    : [];

  async function emitProgress(resolvedCandidates) {
    if (typeof onProgress !== "function" || resolvedCandidates.length === 0) {
      return;
    }

    await onProgress({
      items: resolvedCandidates,
      results: resolvedCandidates.map(
        (item) => resultsByText.get(getAnalysisCacheKey(item)) || null
      ),
      apiBaseUrl,
      backendDurationMs,
      backendStatus
    });
  }

  if (resultsByText.size > 0) {
    const cachedCandidates = items.filter((item) =>
      resultsByText.has(getAnalysisCacheKey(item))
    );
    const cachedOffensiveCandidates = cachedCandidates.filter((item) => {
      const cachedResult = resultsByText.get(getAnalysisCacheKey(item));
      return Boolean(cachedResult?.is_offensive);
    });
    await emitProgress(cachedOffensiveCandidates);
  }

  if (pendingRequests.length > 0) {
    const analysisMode = String(options.analysisMode || "");

    for (const requestBatch of requestBatches) {
      const response = await safeRuntimeSendMessage({
        type: "ANALYZE_TEXT_BATCH",
        texts: requestBatch.map((request) => request.text),
        requestTimeoutMsOverride: requestTimeoutMs || undefined,
        sensitivity: cacheSensitivity,
        analysisMode
      });

      if (!response?.ok) {
        return {
          ok: false,
          error: {
            reason: response?.reason || "ANALYZE_TEXT_BATCH_FAILED",
            errorCode: response?.errorCode || "ANALYZE_TEXT_BATCH_FAILED",
            retryable: Boolean(response?.retryable),
            backendStatus: response?.backendStatus || "degraded"
          },
          apiBaseUrl: response?.apiBaseUrl || apiBaseUrl,
          backendDurationMs,
          requestCount: serviceWorkerRequestCount + Number(response?.requestCount || 0),
          splitRetryCount: serviceWorkerSplitRetryCount + Number(response?.splitRetryCount || 0),
          skippedChunkCount: serviceWorkerSkippedChunkCount + Number(response?.skippedChunkCount || 0),
          failedTextCount: serviceWorkerFailedTextCount + Number(response?.failedTextCount || 0),
          chunkSize: serviceWorkerChunkSize || Number(response?.chunkSize || requestBatchSize),
          lastBackendErrorCode:
            serviceWorkerLastBackendErrorCode || String(response?.lastBackendErrorCode || response?.errorCode || ""),
          requestTimeoutMs: serviceWorkerRequestTimeoutMs || Number(response?.requestTimeoutMs || requestTimeoutMs || 0),
          backendRequestTimings: [
            ...backendRequestTimings,
            ...(Array.isArray(response?.requestTimings) ? response.requestTimings : [])
          ].slice(-12)
        };
      }

      apiBaseUrl = response.apiBaseUrl || apiBaseUrl;
      backendDurationMs += Number(response.durationMs || 0);
      backendStatus = response.backendStatus || backendStatus;
      backendCacheHitCount += Number(response.cacheHitCount || 0);
      serviceWorkerRequestCount += Number(response.requestCount || 0);
      serviceWorkerSplitRetryCount += Number(response.splitRetryCount || 0);
      serviceWorkerSkippedChunkCount += Number(response.skippedChunkCount || 0);
      serviceWorkerFailedTextCount += Number(response.failedTextCount || 0);
      serviceWorkerChunkSize = Number(response.chunkSize || serviceWorkerChunkSize || requestBatchSize);
      serviceWorkerLastBackendErrorCode =
        String(response.lastBackendErrorCode || serviceWorkerLastBackendErrorCode || "");
      serviceWorkerRequestTimeoutMs = Number(
        response.requestTimeoutMs || serviceWorkerRequestTimeoutMs || requestTimeoutMs || 0
      );
      if (Array.isArray(response.requestTimings)) {
        backendRequestTimings.push(...response.requestTimings);
        while (backendRequestTimings.length > 12) {
          backendRequestTimings.shift();
        }
      }
      const resolvedCandidates = [];

      response.results.forEach((result, index) => {
        const request = requestBatch[index];
        if (!request) {
          return;
        }

        const skippedResult = result?.__shieldtextSkipped === true;
        const cacheableResult =
          skippedResult
            ? null
            : result && typeof result === "object"
            ? {
                ...result,
                text: String(result.text || result.original || request.text || "")
              }
            : result || null;
        resultsByText.set(request.key, cacheableResult || null);
        setCachedAnalysis(request.entry, cacheableResult || null);

        for (const item of itemsByText.get(request.key) || []) {
          resolvedCandidates.push(item);
        }
      });

      await emitProgress(resolvedCandidates);
    }
  }

  const orderedResults = items.map((item) => resultsByText.get(getAnalysisCacheKey(item)) || null);

  return {
    ok: true,
    results: orderedResults,
    apiBaseUrl,
    backendDurationMs,
    backendStatus,
    requestedCount: pendingRequests.length,
    requestCount: serviceWorkerRequestCount || requestBatches.length,
    splitRetryCount: serviceWorkerSplitRetryCount,
    skippedChunkCount: serviceWorkerSkippedChunkCount,
    failedTextCount: serviceWorkerFailedTextCount,
    chunkSize: serviceWorkerChunkSize || requestBatchSize,
    requestTimeoutMs: serviceWorkerRequestTimeoutMs || requestTimeoutMs || 0,
    lastBackendErrorCode: serviceWorkerLastBackendErrorCode,
    backendRequestTimings: backendRequestTimings.slice(-12),
    cacheHitCount,
    backendCacheHitCount
  };
}

function buildLocalSpansFromAnalysis(unitText, member, analysis) {
  const analysisSpans = normalizeEvidenceSpans(
    Array.isArray(analysis?.evidence_spans) ? analysis.evidence_spans : [],
    unitText
  );

  const localSpans = [];
  for (const span of analysisSpans) {
    if (span.end <= member.start || span.start >= member.end) {
      continue;
    }

    const localStart = Math.max(0, span.start - member.start);
    const localEnd = Math.min(member.end - member.start, span.end - member.start);
    if (localEnd <= localStart) {
      continue;
    }

    localSpans.push({
      start: localStart,
      end: localEnd,
      score: span.score,
      text: member.candidate.text.slice(localStart, localEnd)
    });
  }

  return localSpans;
}

function buildNodeOutcome(candidate, analysis, settings, evidenceSpans) {
  const scores = {
    profanity: Number(analysis?.scores?.profanity || 0),
    toxicity: Number(analysis?.scores?.toxicity || 0),
    hate: Number(analysis?.scores?.hate || 0)
  };
  const flaggedProfanity = Boolean(analysis?.is_profane);
  const flaggedToxicity = Boolean(analysis?.is_toxic);
  const flaggedHate = Boolean(analysis?.is_hate);
  const categories = [];
  const reasons = [];

  if (settings.categories?.insult && flaggedProfanity) {
    categories.push("insult");
    reasons.push(`모욕 ${Math.round(scores.profanity * 100)}%`);
  }

  if (settings.categories?.abuse && flaggedToxicity) {
    categories.push("abuse");
    reasons.push(`공격 ${Math.round(scores.toxicity * 100)}%`);
  }

  if (settings.categories?.hate && flaggedHate) {
    categories.push("hate");
    reasons.push(`혐오 ${Math.round(scores.hate * 100)}%`);
  }

  const uniqueCategories = [...new Set(categories)];
  if (uniqueCategories.length === 0) {
    return {
      blocked: false,
      categories: [],
      reasons: [],
      scores,
      spans: [],
      matchedKeywords: []
    };
  }

  const normalizedLocalSpans = normalizeEvidenceSpans(
    Array.isArray(evidenceSpans) ? evidenceSpans : [],
    candidate.text
  );
  const displaySpans = expandMaskSpansForDisplay(candidate.text, normalizedLocalSpans);

  if (displaySpans.length === 0) {
    return {
      blocked: false,
      categories: [],
      reasons: [],
      scores,
      spans: [],
      matchedKeywords: []
    };
  }

  return {
    blocked: true,
    categories: uniqueCategories,
    reasons: [...new Set(reasons)],
    scores,
    spans: displaySpans,
    matchedKeywords: []
  };
}

function buildDecisionFromBackend(analysisUnits, analysisResults, settings, backendMeta) {
  const blockedNodeIdSet = new Set();
  const matchedKeywordSet = new Set();
  const categoryHits = emptyCategoryHits();
  const nodeCategoryMap = {};
  const nodeReasonMap = {};
  const nodeScoreMap = {};
  const nodeEvidenceMap = {};
  const nodePendingMap = {};
  const nodeOutcomeMap = {};
  let maskedSpanCount = 0;
  let returnedSpanCount = 0;

  analysisUnits.forEach((unit, index) => {
    const analysis = Array.isArray(analysisResults) ? analysisResults[index] : null;
    returnedSpanCount += normalizeEvidenceSpans(
      Array.isArray(analysis?.evidence_spans) ? analysis.evidence_spans : [],
      unit?.text || ""
    ).length;

    for (const member of unit.members || []) {
      const candidate = member.candidate;
      const localSpans = buildLocalSpansFromAnalysis(unit.text, member, analysis);
      const outcome = buildNodeOutcome(candidate, analysis, settings, localSpans);
      nodeOutcomeMap[candidate.nodeId] = outcome;

      if (!outcome.blocked) {
        continue;
      }

      blockedNodeIdSet.add(candidate.nodeId);
      nodeCategoryMap[candidate.nodeId] = outcome.categories;
      nodeReasonMap[candidate.nodeId] = outcome.reasons;
      nodeScoreMap[candidate.nodeId] = outcome.scores;
      nodeEvidenceMap[candidate.nodeId] = outcome.spans;
      nodePendingMap[candidate.nodeId] = false;
      maskedSpanCount += outcome.spans.length;

      outcome.categories.forEach((category) => {
        categoryHits[category] = Number(categoryHits[category] || 0) + 1;
      });
      outcome.matchedKeywords.forEach((keyword) => {
        matchedKeywordSet.add(keyword);
      });
    }
  });

  return {
    blockedNodeIds: [...blockedNodeIdSet],
    matchedKeywords: [...matchedKeywordSet],
    categoryHits,
    nodeCategoryMap,
    nodeReasonMap,
    nodeScoreMap,
    nodeEvidenceMap,
    nodePendingMap,
    nodeOutcomeMap,
    analyzedNodeCount: Object.keys(nodeOutcomeMap).length,
    blockedNodeCount: blockedNodeIdSet.size,
    backendEndpoint: backendMeta.apiBaseUrl,
    backendDurationMs: backendMeta.backendDurationMs,
    backendStatus: backendMeta.backendStatus || "ready",
    maskedSpanCount,
    returnedSpanCount,
    droppedSpanCount: Math.max(0, returnedSpanCount - maskedSpanCount),
    apiMode: "backend-first"
  };
}

function buildMaskTooltip(categories, reasons, settings) {
  const firstCategory = Array.isArray(categories) && categories[0] ? categories[0] : "abuse";
  const label = CATEGORY_LABELS[firstCategory] || CATEGORY_LABELS.abuse;
  if (settings?.showReason === false) {
    return `${label} 콘텐츠`;
  }

  if (Array.isArray(reasons) && reasons.length > 0) {
    return reasons.join(", ");
  }

  return `${label} 콘텐츠`;
}

function ensureWrapper(state) {
  if (state.wrapper?.isConnected && state.textNode?.parentNode === state.wrapper) {
    return state.wrapper;
  }

  if (!(state.textNode instanceof Text) || !state.textNode.parentNode) {
    return null;
  }

  if (state.textNode.parentElement?.dataset?.shieldtextWrapper === "true") {
    state.wrapper = state.textNode.parentElement;
    return state.wrapper;
  }

  const wrapper = document.createElement("span");
  wrapper.className = "shieldtext-inline-wrapper";
  wrapper.dataset.shieldtextWrapper = "true";
  state.textNode.parentNode.replaceChild(wrapper, state.textNode);
  wrapper.appendChild(state.textNode);
  state.wrapper = wrapper;
  return wrapper;
}

function clearRenderedContent(state) {
  if (!(state.wrapper instanceof Element)) return;

  for (const child of [...state.wrapper.children]) {
    if (child.dataset?.shieldtextRendered === "true") {
      child.remove();
    }
  }

  state.wrapper.removeAttribute("data-shieldtext-state");
  state.wrapper.removeAttribute("data-shieldtext-tooltip");
}

function restoreNodeState(state) {
  if (!state) return;

  clearRenderedContent(state);
  if (state.textNode?.isConnected) {
    state.textNode.nodeValue = state.originalText;
  }

  state.isMasked = false;
  state.isPending = false;
  state.lastDecisionKey = "";
}

// editable overlay helpers are loaded from content-editable-overlay.js

function renderOutcome(state, outcome, settings) {
  if (!state) return;
  if (!outcome?.blocked) {
    restoreNodeState(state);
    return;
  }

  const sourceText = String(state.originalText || "");
  const spans = normalizeEvidenceSpans(outcome.spans, sourceText);
  if (!sourceText || spans.length === 0) {
    restoreNodeState(state);
    return;
  }

  const decisionKey = JSON.stringify({
    text: sourceText,
    categories: outcome.categories,
    interventionMode: settings?.interventionMode || "mask",
    tooltip: buildMaskTooltip(outcome.categories, outcome.reasons, settings),
    spans
  });
  if (decisionKey === state.lastDecisionKey) {
    return;
  }

  const wrapper = ensureWrapper(state);
  if (!wrapper) return;

  clearRenderedContent(state);
  state.textNode.nodeValue = "";

  const renderBox = document.createElement("span");
  renderBox.dataset.shieldtextRendered = "true";
  renderBox.className = "shieldtext-render-box";

  const tooltip = buildMaskTooltip(outcome.categories, outcome.reasons, settings);
  wrapper.dataset.shieldtextState = "blocked";
  wrapper.dataset.shieldtextTooltip = tooltip;

  let cursor = 0;
  for (const span of spans) {
    if (span.start > cursor) {
      renderBox.appendChild(document.createTextNode(sourceText.slice(cursor, span.start)));
    }

    const mask = document.createElement("span");
    mask.className = settings?.interventionMode === "hide"
      ? "shieldtext-inline-hide"
      : "shieldtext-inline-mask";
    mask.textContent = span.text;
    renderBox.appendChild(mask);

    cursor = span.end;
  }

  if (cursor < sourceText.length) {
    renderBox.appendChild(document.createTextNode(sourceText.slice(cursor)));
  }

  wrapper.appendChild(renderBox);
  state.isMasked = true;
  state.isPending = false;
  state.lastDecisionKey = decisionKey;
}

function applyDecision(candidates, decision, settings, options = {}) {
  const expectedGeneration = Number(options.generation || 0);
  const stage = String(options.stage || "foreground");
  for (const candidate of candidates) {
    const state = candidate.state;
    const outcome = decision.nodeOutcomeMap?.[candidate.nodeId];
    if (!state) continue;
    if (expectedGeneration > 0 && Number(state.analysisGeneration || 0) !== expectedGeneration) {
      staleResponseDropCount += 1;
      continue;
    }
    if (shouldSkipCandidateApply(candidate, state, stage)) {
      staleResponseDropCount += 1;
      continue;
    }

    if (candidate.candidateKind === "editable-value") {
      if (!isCandidateGenerationCurrent(candidate, expectedGeneration)) {
        staleResponseDropCount += 1;
        continue;
      }

      const wasMasked = Boolean(state.isMasked);
      const previousDecisionKey = String(state.lastDecisionKey || "");
      renderEditableValueOutcome(
        candidate,
        outcome || {
          blocked: false,
          categories: [],
          reasons: [],
          spans: []
        },
        settings
      );

      if (stage === "foreground" && !wasMasked && state.isMasked) {
        foregroundApplyCount += 1;
      } else if (stage === "reconcile") {
        if (wasMasked && !state.isMasked) {
          reconcileUnmaskCount += 1;
        } else if (
          state.isMasked &&
          previousDecisionKey &&
          previousDecisionKey !== String(state.lastDecisionKey || "")
        ) {
          reconcileOverwriteCount += 1;
        }
      }
      markCandidateApplied(candidate, stage, state.isMasked);
      continue;
    }

    if (!isCandidateGenerationCurrent(candidate, expectedGeneration)) {
      staleResponseDropCount += 1;
      continue;
    }

    const wasMasked = Boolean(state.isMasked);
    const previousDecisionKey = String(state.lastDecisionKey || "");
    renderOutcome(
      state,
      outcome || {
        blocked: false,
        categories: [],
        reasons: [],
        spans: []
      },
      settings
    );

    if (stage === "foreground" && !wasMasked && state.isMasked) {
      foregroundApplyCount += 1;
    } else if (stage === "reconcile") {
      if (wasMasked && !state.isMasked) {
        reconcileUnmaskCount += 1;
      } else if (
        state.isMasked &&
        previousDecisionKey &&
        previousDecisionKey !== String(state.lastDecisionKey || "")
      ) {
        reconcileOverwriteCount += 1;
      }
    }
    markCandidateApplied(candidate, stage, state.isMasked);
  }
}

function createEmptySessionStats() {
  return {
    totalRuns: 0,
    blockedCount: 0,
    falsePositiveCount: 0,
    averageLatencyMs: 0,
    totalAnalyzedCount: 0,
    byCategory: emptyCategoryHits()
  };
}

function serializeFailureReason(reason) {
  if (!reason) {
    return "UNKNOWN_PIPELINE_ERROR";
  }

  if (typeof reason === "string") {
    return reason;
  }

  if (reason instanceof Error) {
    return String(reason.message || reason.name || "UNKNOWN_PIPELINE_ERROR");
  }

  if (typeof reason === "object") {
    if (reason.message) return String(reason.message);
    if (reason.reason) return String(reason.reason);
    if (reason.errorCode) return String(reason.errorCode);
    try {
      return JSON.stringify(reason);
    } catch {
      return String(reason);
    }
  }

  return String(reason);
}

function serializeFailure(reason, errorCode, retryable) {
  return {
    reason: serializeFailureReason(reason),
    errorCode: String(errorCode || "UNKNOWN_PIPELINE_ERROR"),
    retryable: Boolean(retryable)
  };
}

function truncateDiagnosticText(value, maxLength = 160) {
  const text = normalizeText(value);
  if (!text) return "";
  if (text.length <= maxLength) return text;
  return `${text.slice(0, Math.max(0, maxLength - 1))}\u2026`;
}

function summarizeAnalysisResultForDiagnostics(result, sourceText = "") {
  const spans = normalizeEvidenceSpans(result?.evidence_spans, sourceText)
    .slice(0, 3)
    .map((span) => ({
      start: span.start,
      end: span.end,
      text: truncateDiagnosticText(span.text, 40),
      score: Number(span.score || 0)
    }));

  return {
    is_offensive: Boolean(result?.is_offensive),
    is_profane: Boolean(result?.is_profane),
    is_toxic: Boolean(result?.is_toxic),
    is_hate: Boolean(result?.is_hate),
    spanCount: spans.length,
    spans,
    scores: {
      profanity: Number(result?.scores?.profanity || 0),
      toxicity: Number(result?.scores?.toxicity || 0),
      hate: Number(result?.scores?.hate || 0)
    }
  };
}

function buildAnalysisDiagnostics(analysisUnits, analysisResults, meta = {}) {
  const units = Array.isArray(analysisUnits) ? analysisUnits : [];
  const results = Array.isArray(analysisResults) ? analysisResults : [];

  return {
    decisionSource: String(meta.decisionSource || "backend"),
    apiBaseUrl: String(meta.apiBaseUrl || ""),
    backendStatus: String(meta.backendStatus || ""),
    foregroundBackendSource: String(meta.foregroundBackendSource || ""),
    requestedTextCount: Number(meta.requestedTextCount || 0),
    requestCount: Number(meta.requestCount || 0),
    splitRetryCount: Number(meta.splitRetryCount || 0),
    skippedChunkCount: Number(meta.skippedChunkCount || 0),
    failedTextCount: Number(meta.failedTextCount || 0),
    chunkSize: Number(meta.chunkSize || 0),
    requestTimeoutMs: Number(meta.requestTimeoutMs || 0),
    lastBackendErrorCode: String(meta.lastBackendErrorCode || ""),
    cacheHitCount: Number(meta.cacheHitCount || 0),
    backendCacheHitCount: Number(meta.backendCacheHitCount || 0),
    durationMs: Number(meta.durationMs || 0),
    returnedSpanCount: Number(meta.returnedSpanCount || 0),
    appliedSpanCount: Number(meta.appliedSpanCount || 0),
    droppedSpanCount: Number(meta.droppedSpanCount || 0),
    backendRequestTimings: Array.isArray(meta.backendRequestTimings)
      ? meta.backendRequestTimings.slice(-8)
      : [],
    batchSize: units.length,
    items: units.slice(0, 4).map((unit, index) => ({
      text: truncateDiagnosticText(unit?.text, 180),
      memberCount: Array.isArray(unit?.members) ? unit.members.length : 0,
      result: summarizeAnalysisResultForDiagnostics(results[index], unit?.text || "")
    }))
  };
}

async function persistDebug(payload, decision, stats) {
  const runtimeStats = {
    ...(stats || {}),
    ...getRealtimeWorkerDiagnostics()
  };
  const { sessionStats } = await safeStorageLocalGet(["sessionStats"]);
  const currentSessionStats = {
    ...createEmptySessionStats(),
    ...(sessionStats || {}),
    byCategory: {
      ...emptyCategoryHits(),
      ...(sessionStats?.byCategory || {})
    }
  };

  const nextTotalRuns = Number(currentSessionStats.totalRuns || 0) + 1;
  const previousAverageLatencyMs = Number(currentSessionStats.averageLatencyMs || 0);
  const nextAverageLatencyMs = Math.round(
    ((previousAverageLatencyMs * (nextTotalRuns - 1)) + Number(runtimeStats.durationMs || 0)) / nextTotalRuns
  );

  const nextSessionStats = {
    ...currentSessionStats,
    totalRuns: nextTotalRuns,
    blockedCount: Number(currentSessionStats.blockedCount || 0) + Number(runtimeStats.blockedNodeCount || 0),
    totalAnalyzedCount:
      Number(currentSessionStats.totalAnalyzedCount || 0) + Number(runtimeStats.analyzedNodeCount || 0),
    averageLatencyMs: nextAverageLatencyMs,
    byCategory: {
      ...currentSessionStats.byCategory
    }
  };

  Object.entries(decision.categoryHits || {}).forEach(([category, value]) => {
    nextSessionStats.byCategory[category] =
      Number(nextSessionStats.byCategory[category] || 0) + Number(value || 0);
  });

  await safeStorageLocalSet({
    lastPayload: payload,
    lastDecision: decision,
    lastRunAt: Date.now(),
    lastStats: runtimeStats,
    lastPipelineError: null,
    sessionStats: nextSessionStats
  });

  console.info("[청마루] pipeline", {
    analyzedNodeCount: runtimeStats.analyzedNodeCount,
    blockedNodeCount: runtimeStats.blockedNodeCount,
    hostname: runtimeStats.hostname,
    runReason: runtimeStats.runReason,
    backendEndpoint: runtimeStats.backendEndpoint,
    backendStatus: runtimeStats.backendStatus,
    hotPathStatus: runtimeStats.hotPathStatus,
    hotPathErrorCode: runtimeStats.hotPathErrorCode
  });
}

function shouldUseBlockingBackendFallback(runReason) {
  return (
    runReason === "manual" ||
    runReason === "manual-request" ||
    runReason === "manual-request-after-inject"
  );
}

function shouldScheduleBackgroundValidation(runReason) {
  if (
    runReason === "input" ||
    runReason === "input-hot-path" ||
    runReason === "initial-editable-pass" ||
    runReason === "background-validation"
  ) {
    return false;
  }

  if (runReason === "mutation" && isRapidlyChangingRealtimeHost()) {
    return false;
  }

  return true;
}

function shouldPersistHotPathFailure(runReason) {
  if (
    (runReason === "mutation" || runReason === "visibility" || runReason === "route-change") &&
    isRapidlyChangingRealtimeHost()
  ) {
    return false;
  }

  return !isBroadAnalysisReason(runReason);
}

async function persistFailure(failure, stats) {
  const serialized = serializeFailure(failure?.reason, failure?.errorCode, failure?.retryable);
  const runtimeStats = {
    ...(stats || {}),
    ...getRealtimeWorkerDiagnostics()
  };

  await safeStorageLocalSet({
    lastRunAt: Date.now(),
    lastStats: runtimeStats,
    lastPipelineError: {
      ...serialized,
      timestamp: Date.now(),
      hostname: runtimeStats.hostname,
      runReason: runtimeStats.runReason
    }
  });

  console.error(
    `[청마루] pipeline error ${formatDiagnosticError(serialized)} host=${runtimeStats.hostname || "-"} runReason=${runtimeStats.runReason || "-"} hotPath=${runtimeStats.hotPathStatus || "-"} hotPathError=${runtimeStats.hotPathErrorCode || "-"}`
  );
}

async function persistReconcileDecision(payload, decision, stats, pipelineSequence) {
  const currentState = await safeStorageLocalGet(["lastPayload", "lastDecision", "lastStats"]);
  if (Number(currentState?.lastStats?.pipelineSequence || 0) !== Number(pipelineSequence)) {
    return;
  }

  await safeStorageLocalSet({
    lastPayload: payload,
    lastDecision: decision,
    lastStats: {
      ...currentState.lastStats,
      ...getRealtimeWorkerDiagnostics(),
      analyzedNodeCount: stats.analyzedNodeCount,
      backendCacheHitCount: stats.backendCacheHitCount,
      backendDurationMs: stats.backendDurationMs,
      backendEndpoint: stats.backendEndpoint,
      backendReconcileLatencyMs: stats.backendReconcileLatencyMs,
      backendStatus: stats.backendStatus,
      blockedNodeCount: stats.blockedNodeCount,
      lastDecisionSource: "backend-reconcile",
      maskedSpanCount: stats.maskedSpanCount,
      reconcileRequestCount: Number(stats.reconcileRequestCount || 1),
      reconcileQueueDepth: RECONCILE_QUEUE.size,
      lastReconcileDiagnostics: stats.lastReconcileDiagnostics || null
    },
    lastPipelineError: null
  });
}

async function persistReconcileFailure(failure, stats, pipelineSequence) {
  const currentState = await safeStorageLocalGet(["lastStats"]);
  if (Number(currentState?.lastStats?.pipelineSequence || 0) !== Number(pipelineSequence)) {
    return;
  }

  const serialized = serializeFailure(failure?.reason, failure?.errorCode, failure?.retryable);
  await safeStorageLocalSet({
    lastPipelineError: {
      ...serialized,
      timestamp: Date.now(),
      hostname: stats.hostname,
      runReason: stats.runReason
    },
    lastStats: {
      ...currentState.lastStats,
      ...getRealtimeWorkerDiagnostics(),
      backendEndpoint: stats.backendEndpoint,
      backendStatus: stats.backendStatus,
      backendReconcileLatencyMs: stats.backendReconcileLatencyMs,
      lastDecisionSource: "backend-reconcile-failed",
      lastReconcileDiagnostics: stats.lastReconcileDiagnostics || null
    }
  });
}

function getQueuedCandidateKey(candidate) {
  return `${candidate.nodeId}::${candidate.fingerprint}`;
}

function scheduleReconcileFlush(delayMs = RECONCILE_FLUSH_DELAY_MS) {
  const normalizedDelay = Math.max(0, Number(delayMs || RECONCILE_FLUSH_DELAY_MS));
  if (reconcileFlushTimerId && scheduledReconcileDelayMs > 0 && scheduledReconcileDelayMs <= normalizedDelay) {
    return;
  }

  if (reconcileFlushTimerId) {
    window.clearTimeout(reconcileFlushTimerId);
  }

  scheduledReconcileDelayMs = normalizedDelay;

  reconcileFlushTimerId = window.setTimeout(() => {
    scheduledReconcileDelayMs = 0;
    flushReconcileQueue().catch((error) => {
      console.error("[청마루] reconcile queue flush failed", error);
    });
  }, normalizedDelay);
}

function enqueueReconcileCandidates(candidates, pipelineSequence, context, options = {}) {
  for (const candidate of candidates) {
    const queueKey = getQueuedCandidateKey(candidate);
    RECONCILE_QUEUE.set(queueKey, {
      candidate,
      context,
      pipelineSequence
    });
  }

  if (RECONCILE_QUEUE.size > 0) {
    scheduleReconcileFlush(options.delayMs);
  }
}

async function flushReconcileQueue() {
  if (isReconcileRunning || RECONCILE_QUEUE.size === 0) {
    return;
  }

  isReconcileRunning = true;
  if (reconcileFlushTimerId) {
    window.clearTimeout(reconcileFlushTimerId);
    reconcileFlushTimerId = null;
  }
  scheduledReconcileDelayMs = 0;

  try {
    const entries = [...RECONCILE_QUEUE.values()].slice(0, RECONCILE_CHUNK_SIZE);
    for (const entry of entries) {
      RECONCILE_QUEUE.delete(getQueuedCandidateKey(entry.candidate));
    }

    const settings = await loadSettings();
    const latestEntry = entries[entries.length - 1];
    const analysisGeneration = Number(latestEntry?.context?.analysisGeneration || 0);
    const candidates = entries
      .map((entry) => entry.candidate)
      .filter((candidate) => isCandidateGenerationCurrent(candidate, analysisGeneration));

    if (candidates.length === 0) {
      return;
    }

    const units = buildContextualAnalysisUnits(candidates);
    if (units.length === 0) {
      return;
    }

    await reconcileAnalysisUnitsWithBackend(
      units,
      candidates,
      settings,
      buildPayload(candidates, candidates.length, 0),
      Number(latestEntry?.pipelineSequence || 0),
      latestEntry?.context?.runReason || "background-validation",
      latestEntry?.context?.hostname || location.hostname || "unknown",
      latestEntry?.context?.startedAt || performance.now(),
      analysisGeneration
    );
  } finally {
    isReconcileRunning = false;
    if (RECONCILE_QUEUE.size > 0) {
      scheduleReconcileFlush();
    }
  }
}

function markCandidatesSettled(candidates, generation) {
  for (const candidate of candidates) {
    if (!isCandidateGenerationCurrent(candidate, generation)) {
      continue;
    }
    candidate.state.lastFingerprint = candidate.fingerprint;
    candidate.state.hasProcessed = true;
    DIRTY_NODE_IDS.delete(candidate.nodeId);
  }
}

function getDirtyCandidates(candidates, runReason) {
  const forceRefresh =
    runReason === "initial-load" ||
    runReason === "manual-request" ||
    runReason === "manual-request-after-inject" ||
    runReason === "settings-updated" ||
    runReason === "manual";

  return candidates.filter((candidate) => {
    if (forceRefresh) return true;
    if (DIRTY_NODE_IDS.has(candidate.nodeId)) return true;
    if (!candidate.state.hasProcessed) return true;
    return candidate.state.lastFingerprint !== candidate.fingerprint;
  });
}

function collectImmediateInputCandidates() {
  const element = pendingImmediateInputElement;
  pendingImmediateInputElement = null;

  if (!element) {
    return [];
  }

  const candidate = buildEditableValueCandidate(element);
  return candidate ? [candidate] : [];
}

function clearStaleEditableMaskForElement(element) {
  if (!(element instanceof HTMLInputElement) && !(element instanceof HTMLTextAreaElement)) {
    return;
  }

  const editableId = EDITABLE_VALUE_ID_MAP.get(element);
  if (!editableId) {
    return;
  }

  const state = EDITABLE_VALUE_STATE_BY_ID.get(editableId);
  if (!state?.isMasked) {
    return;
  }

  const currentFingerprint = buildFingerprint(normalizeText(getEditableElementText(element)));
  const appliedFingerprint = String(state.lastAppliedFingerprint || "");
  if (!currentFingerprint || !appliedFingerprint || currentFingerprint === appliedFingerprint) {
    return;
  }

  inputMaskResetCount += 1;
  restoreEditableValueState(state);
}

function collectBackendReconcileCandidates(candidates, foregroundCandidates) {
  const reconcileCandidates = new Map();
  const orderedCandidates = Array.isArray(foregroundCandidates) ? foregroundCandidates : [];

  for (const candidate of orderedCandidates) {
    if (!candidate || candidate.candidateKind === "editable-value") {
      continue;
    }
    if (candidate.state?.lastReconcileFingerprint === candidate.fingerprint) {
      continue;
    }

    reconcileCandidates.set(candidate.nodeId, candidate);
    if (reconcileCandidates.size >= RECONCILE_CHUNK_SIZE) {
      break;
    }
  }

  return [...reconcileCandidates.values()];
}

async function executeHotPathForCandidates(candidates, runReason) {
  const nextCandidates = (Array.isArray(candidates) ? candidates : []).filter(Boolean);
  if (extensionContextInvalidated || nextCandidates.length === 0 || isUnsupportedPage()) {
    return { ok: true, skipped: true };
  }

  const settings = await loadSettings();

  if (!settings.enabled) {
    for (const candidate of nextCandidates) {
      if (candidate.candidateKind === "editable-value") {
        restoreEditableValueState(candidate.state);
      } else {
        restoreNodeState(candidate.state);
      }
    }
    return { ok: true, skipped: true };
  }

  const currentCandidates = nextCandidates
    .map((candidate) => {
      if (candidate.candidateKind === "editable-value") {
        return buildEditableValueCandidate(candidate.element);
      }
      return candidate;
    })
    .filter(Boolean);

  if (currentCandidates.length === 0) {
    return { ok: true, skipped: true };
  }

  const analysisGeneration = ++latestAnalysisGeneration;
  markCandidatesAnalysisGeneration(currentCandidates, analysisGeneration);
  const foregroundCandidates = selectForegroundWaveCandidates(currentCandidates, settings, runReason);
  const analysisUnits = buildHotPathAnalysisUnits(foregroundCandidates, {
      containerLimit: MAX_FOREGROUND_WAVE_CONTAINERS,
      boundContext: true,
      preferStandaloneGoogle: true
    });
  if (analysisUnits.length === 0) {
    return { ok: true, skipped: true };
  }
  const unitCandidates = collectUnitCandidates(analysisUnits);

  const startedAt = performance.now();
  const hotPathMeta = await analyzePayloadWithRealtimeWorker(
    analysisUnits,
    settings,
    null,
    {
      requestTimeoutMs: FOREGROUND_ANALYZE_TIMEOUT_MS,
      analysisMode: "foreground"
    }
  );
  const pipelineSequence = ++latestPipelineSequence;
  const hostname = location.hostname || "unknown";

  if (!hotPathMeta.ok) {
    enqueueReconcileCandidates(currentCandidates, pipelineSequence, {
      hostname,
      runReason,
      startedAt,
      analysisGeneration
    });
    return {
      ok: false,
      errorCode: hotPathMeta.error?.errorCode,
      reason: hotPathMeta.error?.reason
    };
  }

  const decision = buildDecisionFromBackend(
    analysisUnits,
    hotPathMeta.results,
    settings,
    {
      apiBaseUrl: hotPathMeta.apiBaseUrl || settings.backendApiBaseUrl,
      backendDurationMs: Number(hotPathMeta.durationMs || 0),
      backendStatus: hotPathMeta.backendStatus || "ready"
    }
  );

  if (Number(decision.maskedSpanCount || 0) > 0) {
    ignoreMutationsUntil = Date.now() + 180;
  }

  applyDecision(unitCandidates, decision, settings, {
    generation: analysisGeneration,
    stage: "foreground"
  });
  markCandidatesSettled(unitCandidates, analysisGeneration);

  const firstMaskLatencyMs =
    Number(decision.maskedSpanCount || 0) > 0 ? Math.round(performance.now() - startedAt) : 0;

  scheduleHotPathStatsPersist({
    analyzedNodeCount: decision.analyzedNodeCount,
    backendEndpoint: hotPathMeta.apiBaseUrl || settings.backendApiBaseUrl,
    backendStatus: hotPathMeta.backendStatus || "ready",
    blockedNodeCount: decision.blockedNodeCount,
    lastDecisionSource: "backend-foreground",
    lastForegroundDiagnostics: buildAnalysisDiagnostics(
      analysisUnits,
      hotPathMeta.results,
      {
        decisionSource: "backend-foreground",
        apiBaseUrl: hotPathMeta.apiBaseUrl || settings.backendApiBaseUrl,
        backendStatus: hotPathMeta.backendStatus || "ready",
        foregroundBackendSource: hotPathMeta.foregroundBackendSource || "",
        requestedTextCount: Number(hotPathMeta.requestedCount || 0),
        requestCount: Number(hotPathMeta.requestCount || 0),
        splitRetryCount: Number(hotPathMeta.splitRetryCount || 0),
        skippedChunkCount: Number(hotPathMeta.skippedChunkCount || 0),
        failedTextCount: Number(hotPathMeta.failedTextCount || 0),
        chunkSize: Number(hotPathMeta.chunkSize || 0),
        requestTimeoutMs: Number(hotPathMeta.requestTimeoutMs || 0),
        lastBackendErrorCode: String(hotPathMeta.lastBackendErrorCode || ""),
        backendRequestTimings: Array.isArray(hotPathMeta.backendRequestTimings)
          ? hotPathMeta.backendRequestTimings
          : [],
        cacheHitCount: Number(hotPathMeta.cacheHitCount || 0),
        backendCacheHitCount: Number(hotPathMeta.backendCacheHitCount || 0),
        durationMs: Number(hotPathMeta.durationMs || 0),
        returnedSpanCount: Number(decision.returnedSpanCount || 0),
        appliedSpanCount: Number(decision.maskedSpanCount || 0),
        droppedSpanCount: Number(decision.droppedSpanCount || 0)
      }
    ),
    durationMs: firstMaskLatencyMs || Number(hotPathMeta.durationMs || 0),
    enabled: true,
    firstMaskLatencyMs,
    hostname,
    hotPathLatencyMs: Number(hotPathMeta.durationMs || 0),
    maskedSpanCount: Number(decision.maskedSpanCount || 0),
    pipelineSequence,
    reconcileQueueDepth: RECONCILE_QUEUE.size,
    runReason,
    visibleContainerBatchSize: analysisUnits.length,
    foregroundCandidateCount: unitCandidates.length,
    workerCacheHitCount: Number(hotPathMeta.cacheHitCount || 0),
    backendCacheHitCount: Number(hotPathMeta.backendCacheHitCount || 0),
    foregroundBackendSource: hotPathMeta.foregroundBackendSource || "",
    foregroundRequestCount: Number(hotPathMeta.requestCount || 0),
    foregroundSplitRetryCount: Number(hotPathMeta.splitRetryCount || 0),
    foregroundSkippedChunkCount: Number(hotPathMeta.skippedChunkCount || 0),
    foregroundFailedTextCount: Number(hotPathMeta.failedTextCount || 0),
    foregroundRequestTimeoutMs: Number(hotPathMeta.requestTimeoutMs || 0),
    foregroundLastBackendErrorCode: String(hotPathMeta.lastBackendErrorCode || ""),
    returnedSpanCount: Number(decision.returnedSpanCount || 0),
    droppedSpanCount: Number(decision.droppedSpanCount || 0)
  });

  const reconcileCandidates = collectBackendReconcileCandidates(currentCandidates, foregroundCandidates)
    .filter((candidate) => isCandidateGenerationCurrent(candidate, analysisGeneration));

  if (reconcileCandidates.length > 0) {
    enqueueReconcileCandidates(reconcileCandidates, pipelineSequence, {
      hostname,
      runReason,
      startedAt,
      analysisGeneration
    }, {
      delayMs:
        Number(decision.blockedNodeCount || 0) > 0
          ? RECONCILE_FAST_FLUSH_DELAY_MS
          : RECONCILE_FLUSH_DELAY_MS
    });
  }

  return {
    ok: true,
    decision,
    latencyMs: Number(hotPathMeta.durationMs || 0),
    pipelineSequence,
    analysisGeneration
  };
}

function scheduleImmediateInputPipeline(element, runReason = "input-hot-path") {
  if (extensionContextInvalidated) return;
  pendingImmediateInputElement = element;

  if (immediateInputTimerId) {
    return;
  }

  immediateInputTimerId = window.requestAnimationFrame(() => {
    immediateInputTimerId = null;
    const candidates = collectImmediateInputCandidates();
    if (candidates.length === 0) {
      return;
    }

    executeHotPathForCandidates(candidates, runReason).catch((error) => {
      console.error("[청마루] immediate input hot path failed", error);
    });
  });
}

function scheduleInitialEditablePass() {
  if (extensionContextInvalidated) return;
  window.requestAnimationFrame(() => {
    const candidates = collectEditableValueCandidates(INITIAL_EDITABLE_PASS_LIMIT);
    if (candidates.length === 0) {
      return;
    }

    executeHotPathForCandidates(candidates, "initial-editable-pass").catch((error) => {
      console.error("[청마루] initial editable hot path failed", error);
    });
  });
}

async function reconcileAnalysisUnitsWithBackend(
  analysisUnits,
  prioritizedCandidates,
  settings,
  payload,
  pipelineSequence,
  runReason,
  hostname,
  startedAt,
  analysisGeneration
) {
  try {
    const reconcileStartedAt = performance.now();
    const unitCandidates = collectUnitCandidates(analysisUnits);
    const fullMeta = await analyzePayloadWithBackend(
      analysisUnits,
      null,
      {
        requestTimeoutMs: RECONCILE_ANALYZE_TIMEOUT_MS,
        analysisMode: "reconcile"
      }
    );

    if (!fullMeta.ok) {
      await persistReconcileFailure(
        fullMeta.error,
        {
          backendEndpoint: fullMeta.apiBaseUrl || settings.backendApiBaseUrl,
          backendReconcileLatencyMs: Math.round(performance.now() - reconcileStartedAt),
          backendStatus: fullMeta.error?.backendStatus || "degraded",
          hostname,
          runReason
        },
        pipelineSequence
      );
      return;
    }

    const decision = buildDecisionFromBackend(
      analysisUnits,
      fullMeta.results,
      settings,
      {
        apiBaseUrl: fullMeta.apiBaseUrl || settings.backendApiBaseUrl,
        backendDurationMs: fullMeta.backendDurationMs,
        backendStatus: fullMeta.backendStatus || "ready"
      }
    );

    ignoreMutationsUntil = Date.now() + 120;
    applyDecision(unitCandidates, decision, settings, {
      generation: analysisGeneration,
      stage: "reconcile"
    });
    markCandidatesSettled(unitCandidates, analysisGeneration);

    await persistReconcileDecision(
      payload,
      decision,
      {
        analyzedNodeCount: decision.analyzedNodeCount,
        backendCacheHitCount: Number(fullMeta.cacheHitCount || 0),
        backendDurationMs: Number(fullMeta.backendDurationMs || 0),
        backendEndpoint: fullMeta.apiBaseUrl || settings.backendApiBaseUrl,
        backendReconcileLatencyMs: Math.round(performance.now() - startedAt),
        backendStatus: fullMeta.backendStatus || "ready",
        blockedNodeCount: decision.blockedNodeCount,
        maskedSpanCount: Number(decision.maskedSpanCount || 0),
        lastReconcileDiagnostics: buildAnalysisDiagnostics(
          analysisUnits,
          fullMeta.results,
          {
            decisionSource: "backend-reconcile",
            apiBaseUrl: fullMeta.apiBaseUrl || settings.backendApiBaseUrl,
            backendStatus: fullMeta.backendStatus || "ready",
            foregroundBackendSource: getForegroundBackendSource(fullMeta),
            requestedTextCount: Number(fullMeta.requestedCount || 0),
            requestCount: Number(fullMeta.requestCount || 0),
            splitRetryCount: Number(fullMeta.splitRetryCount || 0),
            skippedChunkCount: Number(fullMeta.skippedChunkCount || 0),
            failedTextCount: Number(fullMeta.failedTextCount || 0),
            chunkSize: Number(fullMeta.chunkSize || 0),
            requestTimeoutMs: Number(fullMeta.requestTimeoutMs || 0),
            lastBackendErrorCode: String(fullMeta.lastBackendErrorCode || ""),
            backendRequestTimings: Array.isArray(fullMeta.backendRequestTimings)
              ? fullMeta.backendRequestTimings
              : [],
            cacheHitCount: Number(fullMeta.cacheHitCount || 0),
            backendCacheHitCount: Number(fullMeta.backendCacheHitCount || 0),
            durationMs: Number(fullMeta.backendDurationMs || 0),
            returnedSpanCount: Number(decision.returnedSpanCount || 0),
            appliedSpanCount: Number(decision.maskedSpanCount || 0),
            droppedSpanCount: Number(decision.droppedSpanCount || 0)
          }
        )
      },
      pipelineSequence
    );
  } catch (error) {
    await persistReconcileFailure(
      {
        reason: String(error?.message || error || "BACKEND_RECONCILE_FAILED"),
        errorCode: "BACKEND_RECONCILE_FAILED",
        retryable: true
      },
      {
        backendEndpoint: settings.backendApiBaseUrl,
        backendReconcileLatencyMs: Math.round(performance.now() - startedAt),
        backendStatus: "degraded",
        hostname,
        runReason,
        lastReconcileDiagnostics: {
          decisionSource: "backend-reconcile-failed",
          batchSize: Array.isArray(analysisUnits) ? analysisUnits.length : 0,
          apiBaseUrl: settings.backendApiBaseUrl,
          backendStatus: "degraded"
        }
      },
      pipelineSequence
    );
  }
}

async function executePipeline(runReason) {
  if (isUnsupportedPage()) {
    return { ok: false, reason: "UNSUPPORTED_PAGE", errorCode: "UNSUPPORTED_PAGE" };
  }

  if (isPipelineRunning) {
    queuedReason = runReason;
    return { ok: true, queued: true };
  }

  isPipelineRunning = true;
  const pipelineSequence = ++latestPipelineSequence;
  const startedAt = performance.now();

  try {
    const settings = await loadSettings();
    const hostname = location.hostname || "unknown";

    if (!settings.enabled) {
      ignoreMutationsUntil = Date.now() + 220;
      for (const state of NODE_STATE_BY_ID.values()) {
        restoreNodeState(state);
      }
      for (const state of EDITABLE_VALUE_STATE_BY_ID.values()) {
        restoreEditableValueState(state);
      }

      const payload = buildPayload([], 0, 0);
      const decision = {
        blockedNodeIds: [],
        matchedKeywords: [],
        categoryHits: emptyCategoryHits(),
        nodeCategoryMap: {},
        nodeReasonMap: {},
        nodeScoreMap: {},
        nodeEvidenceMap: {},
        nodePendingMap: {},
        nodeOutcomeMap: {},
        analyzedNodeCount: 0,
        blockedNodeCount: 0,
        backendEndpoint: settings.backendApiBaseUrl,
        backendDurationMs: 0,
        backendStatus: "disabled",
        apiMode: "disabled"
      };

      const stats = {
        hostname,
        analyzedNodeCount: 0,
        blockedNodeCount: 0,
        matchedKeywordCount: 0,
        durationMs: Math.round(performance.now() - startedAt),
        runReason,
        enabled: false,
        backendEndpoint: settings.backendApiBaseUrl,
        backendStatus: "disabled",
        totalCandidateCount: 0,
        requestedAnalysisCount: 0,
        cacheHitCount: 0
      };

      await persistDebug(payload, decision, stats);
      return { ok: true, stats };
    }

    const immediateInputCandidates = runReason === "input" ? collectImmediateInputCandidates() : [];
    const candidates = immediateInputCandidates.length > 0 ? immediateInputCandidates : collectCandidates();
    const dirtyCandidates = immediateInputCandidates.length > 0
      ? immediateInputCandidates
      : getDirtyCandidates(candidates, runReason);
    const prioritizedCandidates = selectCandidatesForRun(
      dirtyCandidates,
      settings,
      runReason
    );
    const analysisGeneration = ++latestAnalysisGeneration;
    markCandidatesAnalysisGeneration(prioritizedCandidates, analysisGeneration);
    const foregroundCandidates = selectForegroundWaveCandidates(
      prioritizedCandidates,
      settings,
      runReason
    );
    const foregroundUnitBuildStartedAt = performance.now();
    const analysisUnits = buildHotPathAnalysisUnits(foregroundCandidates, {
      containerLimit: isBroadAnalysisReason(runReason)
        ? MAX_HOT_PATH_CONTAINERS
        : MAX_FOREGROUND_WAVE_CONTAINERS,
      boundContext: true,
      preferStandaloneGoogle: true
    });
    const unitCandidates = collectUnitCandidates(analysisUnits);
    const analyzedCandidateIds = new Set(unitCandidates.map((candidate) => candidate.nodeId));
    const foregroundUnitBuildMs = Math.round(performance.now() - foregroundUnitBuildStartedAt);
    const droppedCandidateCount = Math.max(0, dirtyCandidates.length - prioritizedCandidates.length);
    const remainingPrioritizedCandidateCount = prioritizedCandidates.filter(
      (candidate) => !analyzedCandidateIds.has(candidate.nodeId)
    ).length;
    const contextualReconcileCandidates = runReason === "background-validation"
      ? []
      : collectBackendReconcileCandidates(
          prioritizedCandidates,
          foregroundCandidates
        ).filter((candidate) => isCandidateGenerationCurrent(candidate, analysisGeneration));

    if (prioritizedCandidates.length === 0 || foregroundCandidates.length === 0 || analysisUnits.length === 0) {
      const payload = buildPayload([], candidates.length, 0);
      const decision = {
        blockedNodeIds: [],
        matchedKeywords: [],
        categoryHits: emptyCategoryHits(),
        nodeCategoryMap: {},
        nodeReasonMap: {},
        nodeScoreMap: {},
        nodeEvidenceMap: {},
        nodePendingMap: {},
        nodeOutcomeMap: {},
        analyzedNodeCount: 0,
        blockedNodeCount: 0,
        backendEndpoint: settings.backendApiBaseUrl,
        backendDurationMs: 0,
        backendStatus: "ready",
        apiMode: "backend-first"
      };
      const stats = {
        hostname,
        analyzedNodeCount: 0,
        blockedNodeCount: 0,
        matchedKeywordCount: 0,
        durationMs: Math.round(performance.now() - startedAt),
        runReason,
        enabled: true,
        backendEndpoint: settings.backendApiBaseUrl,
        backendStatus: "ready",
        backendDurationMs: 0,
        foregroundBackendLatencyMs: 0,
        foregroundBackendSource: "fallback-none",
        cacheHitCount: 0,
        foregroundUnitBuildMs,
        firstPaintMaskMs: 0,
        reconcileQueueDepth: RECONCILE_QUEUE.size,
        reconcileSkippedCount: 0,
        requestedAnalysisCount: 0,
        totalCandidateCount: candidates.length,
        droppedCandidateCount: 0,
        firstMaskLatencyMs: 0,
        foregroundRequestCount: 0,
        reconcileRequestCount: 0,
        lastDecisionSource: "backend-foreground",
        lastForegroundDiagnostics: {
          decisionSource: "backend-foreground",
          apiBaseUrl: settings.backendApiBaseUrl,
          backendStatus: "ready",
          foregroundBackendSource: "fallback-none",
          batchSize: 0,
          items: []
        }
      };
      await persistDebug(payload, decision, stats);

      if (
        dirtyCandidates.length > 0 &&
        shouldScheduleBackgroundValidation(runReason) &&
        !queuedReason
      ) {
        queuedReason = "background-validation";
      }

      return { ok: true, stats };
    }

    let firstMaskLatencyMs = 0;
    const payload = buildPayload(foregroundCandidates, candidates.length, droppedCandidateCount);
    const hotPathMeta = await analyzePayloadWithRealtimeWorker(
      analysisUnits,
      settings,
      async (partialMeta) => {
        if (pipelineSequence !== latestPipelineSequence) {
          return;
        }

        const partialDecision = buildDecisionFromBackend(
          partialMeta.items,
          partialMeta.results,
          settings,
          partialMeta
        );

        if (!firstMaskLatencyMs && Number(partialDecision.maskedSpanCount || 0) > 0) {
          firstMaskLatencyMs = Math.round(performance.now() - startedAt);
        }

        ignoreMutationsUntil = Date.now() + 220;
        applyDecision(
          collectUnitCandidates(partialMeta.items),
          partialDecision,
          settings,
          {
            generation: analysisGeneration,
            stage: "foreground"
          }
        );
      },
      {
        requestTimeoutMs:
          runReason === "background-validation"
            ? RECONCILE_ANALYZE_TIMEOUT_MS
            : FOREGROUND_ANALYZE_TIMEOUT_MS,
        suppressHotPathFailure: runReason === "background-validation",
        analysisMode:
          runReason === "background-validation"
            ? "background-validation"
            : "foreground"
      }
    );
    let stats = null;

    if (!hotPathMeta.ok) {
      const failureStats = {
        hostname,
        analyzedNodeCount: analysisUnits.length,
        blockedNodeCount: 0,
        matchedKeywordCount: 0,
        durationMs: Math.round(performance.now() - startedAt),
        runReason,
        enabled: true,
        backendEndpoint: hotPathMeta.apiBaseUrl || settings.backendApiBaseUrl,
        backendStatus: hotPathMeta.backendStatus || "degraded",
        foregroundBackendLatencyMs: Number(hotPathMeta.durationMs || 0),
        foregroundBackendSource: hotPathMeta.foregroundBackendSource || "fallback-none",
        foregroundRequestCount: Number(hotPathMeta.requestCount || 0),
        foregroundSplitRetryCount: Number(hotPathMeta.splitRetryCount || 0),
        foregroundSkippedChunkCount: Number(hotPathMeta.skippedChunkCount || 0),
        foregroundFailedTextCount: Number(hotPathMeta.failedTextCount || 0),
        foregroundRequestTimeoutMs: Number(hotPathMeta.requestTimeoutMs || 0),
        foregroundLastBackendErrorCode: String(hotPathMeta.lastBackendErrorCode || ""),
        reconcileRequestCount: 0,
        totalCandidateCount: candidates.length,
        requestedAnalysisCount: analysisUnits.length,
        cacheHitCount: 0,
        lastDecisionSource: "backend-foreground-failed",
        lastForegroundDiagnostics: {
          decisionSource: "backend-foreground-failed",
          apiBaseUrl: hotPathMeta.apiBaseUrl || settings.backendApiBaseUrl,
          backendStatus: hotPathMeta.backendStatus || "degraded",
          foregroundBackendSource: hotPathMeta.foregroundBackendSource || "fallback-none",
          durationMs: Number(hotPathMeta.durationMs || 0),
          requestCount: Number(hotPathMeta.requestCount || 0),
          splitRetryCount: Number(hotPathMeta.splitRetryCount || 0),
          skippedChunkCount: Number(hotPathMeta.skippedChunkCount || 0),
          failedTextCount: Number(hotPathMeta.failedTextCount || 0),
          chunkSize: Number(hotPathMeta.chunkSize || 0),
          requestTimeoutMs: Number(hotPathMeta.requestTimeoutMs || 0),
          lastBackendErrorCode: String(hotPathMeta.lastBackendErrorCode || ""),
          backendRequestTimings: Array.isArray(hotPathMeta.backendRequestTimings)
            ? hotPathMeta.backendRequestTimings.slice(-8)
            : [],
          batchSize: analysisUnits.length,
          items: analysisUnits.slice(0, 4).map((unit) => ({
            text: truncateDiagnosticText(unit?.text, 180),
            memberCount: Array.isArray(unit?.members) ? unit.members.length : 0
          }))
        }
      };

      if (shouldPersistHotPathFailure(runReason)) {
        await persistFailure(hotPathMeta.error, failureStats);
      } else {
        scheduleHotPathStatsPersist({
          ...failureStats,
          lastDecisionSource: "backend-foreground-transient-failed",
          lastForegroundDiagnostics: failureStats.lastForegroundDiagnostics
        });
      }
      return {
        ok: false,
        reason: hotPathMeta.error?.reason,
        errorCode: hotPathMeta.error?.errorCode,
        retryable: hotPathMeta.error?.retryable
      };
    }

    const decision = buildDecisionFromBackend(
      analysisUnits,
      hotPathMeta.results,
      settings,
      {
        apiBaseUrl: hotPathMeta.apiBaseUrl || settings.backendApiBaseUrl,
        backendDurationMs: Number(hotPathMeta.durationMs || 0),
        backendStatus: hotPathMeta.backendStatus || "ready"
      }
    );

    if (Number(decision.maskedSpanCount || 0) > 0 && !firstMaskLatencyMs) {
      firstMaskLatencyMs = Math.round(performance.now() - startedAt);
    }

    ignoreMutationsUntil = Date.now() + 220;
    applyDecision(unitCandidates, decision, settings, {
      generation: analysisGeneration,
      stage: "foreground"
    });
    markCandidatesSettled(unitCandidates, analysisGeneration);

    if (contextualReconcileCandidates.length > 0) {
      enqueueReconcileCandidates(
        contextualReconcileCandidates,
        pipelineSequence,
        {
          hostname,
          runReason,
          startedAt,
          analysisGeneration
        },
        {
          delayMs:
            Number(decision.blockedNodeCount || 0) > 0
              ? RECONCILE_FAST_FLUSH_DELAY_MS
              : RECONCILE_FLUSH_DELAY_MS
        }
      );
    }

    stats = {
      hostname,
      analyzedNodeCount: decision.analyzedNodeCount,
      blockedNodeCount: decision.blockedNodeCount,
      matchedKeywordCount: decision.matchedKeywords.length,
      maskedSpanCount: Number(decision.maskedSpanCount || 0),
      durationMs: Math.round(performance.now() - startedAt),
      firstMaskLatencyMs,
      runReason,
      enabled: true,
      backendEndpoint: hotPathMeta.apiBaseUrl || settings.backendApiBaseUrl,
      backendStatus: hotPathMeta.backendStatus || "ready",
      backendDurationMs: Number(hotPathMeta.durationMs || 0),
      backendCacheHitCount: Number(hotPathMeta.backendCacheHitCount || 0),
      backendReconcileLatencyMs: 0,
      cacheHitCount: Number(hotPathMeta.cacheHitCount || 0),
      foregroundRequestCount: Number(hotPathMeta.requestCount || 0),
      foregroundSplitRetryCount: Number(hotPathMeta.splitRetryCount || 0),
      foregroundSkippedChunkCount: Number(hotPathMeta.skippedChunkCount || 0),
      foregroundFailedTextCount: Number(hotPathMeta.failedTextCount || 0),
      foregroundRequestTimeoutMs: Number(hotPathMeta.requestTimeoutMs || 0),
      foregroundLastBackendErrorCode: String(hotPathMeta.lastBackendErrorCode || ""),
      reconcileRequestCount: contextualReconcileCandidates.length > 0 ? 1 : 0,
      foregroundUnitBuildMs,
      firstPaintMaskMs: firstMaskLatencyMs,
      hotPathLatencyMs: Number(hotPathMeta.durationMs || 0),
      foregroundBackendLatencyMs: Number(hotPathMeta.durationMs || 0),
      foregroundBackendSource: hotPathMeta.foregroundBackendSource || "",
      requestedAnalysisCount: Number(hotPathMeta.requestedCount || 0),
      reconcileQueueDepth: RECONCILE_QUEUE.size,
      reconcileSkippedCount: 0,
      totalCandidateCount: candidates.length,
      droppedCandidateCount,
      pipelineSequence,
      visibleContainerBatchSize: analysisUnits.length,
      foregroundCandidateCount: unitCandidates.length,
      contextualCandidateCount: contextualReconcileCandidates.length,
      remainingPrioritizedCandidateCount,
      workerCacheHitCount: Number(hotPathMeta.cacheHitCount || 0),
      returnedSpanCount: Number(decision.returnedSpanCount || 0),
      droppedSpanCount: Number(decision.droppedSpanCount || 0),
      lastDecisionSource: "backend-foreground",
      lastForegroundDiagnostics: buildAnalysisDiagnostics(
        analysisUnits,
        hotPathMeta.results,
        {
          decisionSource: "backend-foreground",
          apiBaseUrl: hotPathMeta.apiBaseUrl || settings.backendApiBaseUrl,
          backendStatus: hotPathMeta.backendStatus || "ready",
          foregroundBackendSource: hotPathMeta.foregroundBackendSource || "",
          requestedTextCount: Number(hotPathMeta.requestedCount || 0),
          requestCount: Number(hotPathMeta.requestCount || 0),
          splitRetryCount: Number(hotPathMeta.splitRetryCount || 0),
          skippedChunkCount: Number(hotPathMeta.skippedChunkCount || 0),
          failedTextCount: Number(hotPathMeta.failedTextCount || 0),
          chunkSize: Number(hotPathMeta.chunkSize || 0),
          requestTimeoutMs: Number(hotPathMeta.requestTimeoutMs || 0),
          lastBackendErrorCode: String(hotPathMeta.lastBackendErrorCode || ""),
          backendRequestTimings: Array.isArray(hotPathMeta.backendRequestTimings)
            ? hotPathMeta.backendRequestTimings
            : [],
          cacheHitCount: Number(hotPathMeta.cacheHitCount || 0),
          backendCacheHitCount: Number(hotPathMeta.backendCacheHitCount || 0),
          durationMs: Number(hotPathMeta.durationMs || 0),
          returnedSpanCount: Number(decision.returnedSpanCount || 0),
          appliedSpanCount: Number(decision.maskedSpanCount || 0),
          droppedSpanCount: Number(decision.droppedSpanCount || 0)
        }
      )
    };

    await persistDebug(payload, decision, stats);

    if (
      shouldScheduleBackgroundValidation(runReason) &&
      remainingPrioritizedCandidateCount > 0 &&
      !queuedReason
    ) {
      queuedReason = "background-validation";
    }

    return { ok: true, stats };
  } catch (error) {
    const failure = serializeFailure(error?.message || error, error?.errorCode, error?.retryable);
    const failureStats = {
      hostname: location.hostname || "unknown",
      analyzedNodeCount: 0,
      blockedNodeCount: 0,
      matchedKeywordCount: 0,
      durationMs: Math.round(performance.now() - startedAt),
      runReason,
      enabled: true,
      backendEndpoint: "",
      backendStatus: "degraded"
    };

    if (shouldPersistHotPathFailure(runReason)) {
      await persistFailure(failure, failureStats);
    } else {
      scheduleHotPathStatsPersist({
        ...failureStats,
        lastDecisionSource: "backend-transient-failed",
        hotPathErrorCode: failure.errorCode,
        hotPathStatus: "degraded"
      });
    }
    return {
      ok: false,
      reason: failure.reason,
      errorCode: failure.errorCode,
      retryable: failure.retryable
    };
  } finally {
    isPipelineRunning = false;

    if (queuedReason) {
      const scheduledReason = queuedReason;
      queuedReason = null;
      schedulePipeline(scheduledReason);
    }
  }
}

function schedulePipeline(reason) {
  if (extensionContextInvalidated || isUnsupportedPage()) return;

  if (debounceTimerId) {
    window.clearTimeout(debounceTimerId);
  }

  let delay = PIPELINE_DEBOUNCE_MS;
  if (reason === "input") {
    delay = INPUT_PIPELINE_DEBOUNCE_MS;
  } else if (reason === "visibility" || reason === "mutation") {
    delay = VISIBILITY_PIPELINE_DEBOUNCE_MS;
  } else if (reason === "background-validation") {
    delay = BACKGROUND_PIPELINE_DEBOUNCE_MS;
  }

  debounceTimerId = window.setTimeout(() => {
    debounceTimerId = null;
    executePipeline(reason);
  }, delay);
}

function scheduleStartupFollowupPipelines() {
  for (const delayMs of STARTUP_FOLLOWUP_DELAYS_MS) {
    window.setTimeout(() => {
      if (extensionContextInvalidated || isUnsupportedPage()) return;
      const registeredCount = registerTextNodesInTree(document.body, {
        onlyVisible: true,
        limit: MAX_INITIAL_TEXT_NODES
      });
      if (registeredCount > 0) {
        schedulePipeline("visibility");
      }
    }, delayMs);
  }
}

function invalidatePendingAnalysisForNavigation() {
  latestAnalysisGeneration += 1;
  latestPipelineSequence += 1;

  for (const state of NODE_STATE_BY_ID.values()) {
    state.analysisGeneration = latestAnalysisGeneration;
    state.lastAppliedStage = "";
  }

  for (const state of EDITABLE_VALUE_STATE_BY_ID.values()) {
    state.analysisGeneration = latestAnalysisGeneration;
    state.lastAppliedStage = "";
  }
}

function refreshCurrentRouteCandidates() {
  if (extensionContextInvalidated || isUnsupportedPage() || !document.body) {
    return 0;
  }

  cleanupDisconnectedStates();
  const registeredCount = registerTextNodesInTree(document.body, {
    markDirty: true,
    onlyVisible: true,
    limit: MAX_INITIAL_TEXT_NODES
  });
  scheduleInitialEditablePass();
  scheduleStartupFollowupPipelines();
  return registeredCount;
}

function runRouteRefreshWave(sequence) {
  if (sequence !== routeRefreshSequence) {
    return;
  }

  const registeredCount = refreshCurrentRouteCandidates();
  if (registeredCount > 0) {
    schedulePipeline("route-change");
  } else {
    scheduleScrollVisibilityRefresh();
  }
}

function clearRouteRefreshFollowups() {
  for (const timeoutId of ROUTE_REFRESH_TIMEOUT_IDS) {
    window.clearTimeout(timeoutId);
  }
  ROUTE_REFRESH_TIMEOUT_IDS.clear();
}

function scheduleRouteRefreshFollowups(sequence) {
  clearRouteRefreshFollowups();

  for (const delayMs of ROUTE_CHANGE_FOLLOWUP_DELAYS_MS) {
    const timeoutId = window.setTimeout(() => {
      ROUTE_REFRESH_TIMEOUT_IDS.delete(timeoutId);
      runRouteRefreshWave(sequence);
    }, delayMs);
    ROUTE_REFRESH_TIMEOUT_IDS.add(timeoutId);
  }
}

function scheduleRouteRefresh(reason = "route-change") {
  if (extensionContextInvalidated || isUnsupportedPage()) {
    return;
  }

  const currentHref = String(location.href || "");
  const isActualRouteChange = currentHref !== lastObservedLocationHref;
  const allowSameRouteRefresh = [
    "pageshow",
    "load",
    "readystatechange",
    "turbo-load",
    "yt-navigate-finish"
  ].includes(String(reason || ""));
  if (!isActualRouteChange && !allowSameRouteRefresh) {
    return;
  }

  if (isActualRouteChange) {
    lastObservedLocationHref = currentHref;
    invalidatePendingAnalysisForNavigation();
  }
  const sequence = ++routeRefreshSequence;

  if (routeRefreshFrameId) {
    window.cancelAnimationFrame(routeRefreshFrameId);
  }

  routeRefreshFrameId = window.requestAnimationFrame(() => {
    routeRefreshFrameId = null;
    runRouteRefreshWave(sequence);
    scheduleRouteRefreshFollowups(sequence);
  });
}

function initializeNavigationListeners() {
  if (navigationListenersInitialized) {
    return;
  }
  navigationListenersInitialized = true;

  const wrapHistoryMethod = (methodName) => {
    const original = history?.[methodName];
    if (typeof original !== "function") {
      return;
    }

    history[methodName] = function patchedHistoryMethod(...args) {
      const result = original.apply(this, args);
      window.setTimeout(() => scheduleRouteRefresh(methodName), 0);
      return result;
    };
  };

  try {
    wrapHistoryMethod("pushState");
    wrapHistoryMethod("replaceState");
  } catch {
    // Some pages expose non-writable history methods in the isolated world.
  }

  window.addEventListener("popstate", () => scheduleRouteRefresh("popstate"), true);
  window.addEventListener("hashchange", () => scheduleRouteRefresh("hashchange"), true);
  window.addEventListener("pageshow", () => scheduleRouteRefresh("pageshow"), true);
  window.addEventListener("load", () => scheduleRouteRefresh("load"), true);
  document.addEventListener(
    "readystatechange",
    () => {
      if (document.readyState === "interactive" || document.readyState === "complete") {
        scheduleRouteRefresh("readystatechange");
      }
    },
    true
  );
  document.addEventListener("turbo:load", () => scheduleRouteRefresh("turbo-load"), true);
  document.addEventListener("yt-navigate-finish", () => scheduleRouteRefresh("yt-navigate-finish"), true);
  try {
    if (window.navigation?.addEventListener) {
      window.navigation.addEventListener(
        "currententrychange",
        () => scheduleRouteRefresh("navigation-api"),
        true
      );
      window.navigation.addEventListener(
        "navigate",
        () => window.setTimeout(() => scheduleRouteRefresh("navigation-api"), 0),
        true
      );
    }
  } catch {
    // Navigation API is optional and can be blocked by the page.
  }
  document.addEventListener(
    "visibilitychange",
    () => {
      if (document.visibilityState === "visible") {
        scheduleRouteRefresh("pageshow");
      }
    },
    true
  );

  navigationPollTimerId = window.setInterval(() => {
    if (extensionContextInvalidated || isUnsupportedPage()) {
      return;
    }

    if (String(location.href || "") !== lastObservedLocationHref) {
      scheduleRouteRefresh("location-poll");
    }
  }, NAVIGATION_POLL_INTERVAL_MS);
}

function refreshVisibleCandidateRegistrations() {
  let registeredCount = 0;

  if (isGoogleSearchPage()) {
    const containers = getGoogleVisibleAnalysisContainers(MAX_HOT_PATH_CONTAINERS);
    for (const container of containers) {
      registeredCount += registerTextNodesInTree(container, {
        onlyVisible: true,
        limit: MAX_GOOGLE_CANDIDATES_PER_CONTAINER
      });
    }
    return registeredCount;
  }

  return registerTextNodesInTree(document.body, {
    onlyVisible: true,
    limit: SCROLL_REFRESH_TEXT_NODE_LIMIT
  });
}

function scheduleScrollVisibilityRefresh() {
  if (scrollVisibilityRefreshFrameId) {
    return;
  }

  scrollVisibilityRefreshFrameId = window.requestAnimationFrame(() => {
    scrollVisibilityRefreshFrameId = null;
    if (extensionContextInvalidated || isUnsupportedPage()) {
      return;
    }

    const registeredCount = refreshVisibleCandidateRegistrations();
    if (registeredCount > 0) {
      schedulePipeline("visibility");
    }
  });
}

function scheduleSuppressedMutationRefresh() {
  if (suppressedMutationRefreshTimerId || extensionContextInvalidated || isUnsupportedPage()) {
    return;
  }

  const delayMs = Math.max(16, Math.min(260, ignoreMutationsUntil - Date.now() + 24));
  suppressedMutationRefreshTimerId = window.setTimeout(() => {
    suppressedMutationRefreshTimerId = null;
    if (extensionContextInvalidated || isUnsupportedPage()) {
      return;
    }

    scheduleScrollVisibilityRefresh();
  }, delayMs);
}

function markTextNodeDirty(textNode) {
  if (!(textNode instanceof Text)) return false;
  const state = registerTextNode(textNode);
  if (!state) return false;
  const wasDirty = DIRTY_NODE_IDS.has(state.nodeId);
  DIRTY_NODE_IDS.add(state.nodeId);
  return !wasDirty;
}

function markDirtyFromTarget(target) {
  if (target instanceof Text) {
    if (shouldSkipTextNodeParent(target.parentElement)) return false;
    return markTextNodeDirty(target);
  }

  if (!(target instanceof Element)) return false;
  if (shouldSkipTextNodeParent(target)) return false;
  const registeredCount = registerTextNodesInTree(target, {
    markDirty: true,
    onlyVisible: true,
    limit: MAX_DIRTY_TEXT_NODES_PER_MUTATION
  });
  return registeredCount > 0;
}

function initializeVisibilityObserver() {
  if (visibilityObserver) {
    visibilityObserver.disconnect();
  }

  visibilityObserver = new IntersectionObserver(
    (entries) => {
      let shouldSchedule = false;

      entries.forEach((entry) => {
        const linkedNodeIds = OBSERVED_ELEMENT_NODE_IDS.get(entry.target);
        if (!linkedNodeIds) return;

        linkedNodeIds.forEach((nodeId) => {
          const wasVisible = VISIBLE_NODE_IDS.has(nodeId);
          if (entry.isIntersecting) {
            VISIBLE_NODE_IDS.add(nodeId);
            if (!wasVisible) {
              DIRTY_NODE_IDS.add(nodeId);
              shouldSchedule = true;
            }
          } else {
            VISIBLE_NODE_IDS.delete(nodeId);
          }
        });
      });

      if (shouldSchedule) {
        schedulePipeline("visibility");
      }
    },
    {
      root: null,
      rootMargin: `${VIEWPORT_BUFFER_PX}px 0px ${VIEWPORT_BUFFER_PX}px 0px`,
      threshold: 0.01
    }
  );
}

function initializeObserver() {
  if (extensionContextInvalidated || !document.documentElement) return;
  if (observer) observer.disconnect();

  observer = new MutationObserver((mutationList) => {
    if (!mutationList || mutationList.length === 0) return;
    if (Date.now() < ignoreMutationsUntil) {
      scheduleSuppressedMutationRefresh();
      return;
    }
    let shouldSchedule = false;
    let sawAddedContent = false;

    mutationList.forEach((mutation) => {
      shouldSchedule = markDirtyFromTarget(mutation.target) || shouldSchedule;
      mutation.addedNodes.forEach((node) => {
        if (node instanceof Text || node instanceof Element || node instanceof DocumentFragment) {
          sawAddedContent = true;
        }
        shouldSchedule = markDirtyFromTarget(node) || shouldSchedule;
      });
    });

    if (shouldSchedule) {
      schedulePipeline("mutation");
    } else if (sawAddedContent) {
      scheduleScrollVisibilityRefresh();
    }
  });

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true
  });
}

function initializeInputListeners() {
  document.addEventListener(
    "input",
    (event) => {
      const target = event.target;
      if (!(target instanceof HTMLInputElement) && !(target instanceof HTMLTextAreaElement)) {
        return;
      }

      const candidate = buildEditableValueCandidate(target);
      if (!candidate) {
        const existingId = EDITABLE_VALUE_ID_MAP.get(target);
        const state = existingId ? EDITABLE_VALUE_STATE_BY_ID.get(existingId) : null;
        if (state) {
          restoreEditableValueState(state);
        }
        return;
      }

      clearStaleEditableMaskForElement(target);
      pendingImmediateInputElement = target;
      DIRTY_NODE_IDS.add(candidate.nodeId);
      scheduleImmediateInputPipeline(target, "input-hot-path");
    },
    true
  );

  document.addEventListener(
    "compositionend",
    (event) => {
      const target = event.target;
      if (!(target instanceof HTMLInputElement) && !(target instanceof HTMLTextAreaElement)) {
        return;
      }

      clearStaleEditableMaskForElement(target);
      pendingImmediateInputElement = target;
      scheduleImmediateInputPipeline(target, "input-hot-path");
    },
    true
  );

  document.addEventListener(
    "scroll",
    (event) => {
      const target = event.target;
      if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
        scheduleEditableOverlaySync();
      }
    },
    true
  );

  document.addEventListener(
    "selectionchange",
    () => {
      const activeElement = document.activeElement;
      if (activeElement instanceof HTMLInputElement || activeElement instanceof HTMLTextAreaElement) {
        const activeId = EDITABLE_VALUE_ID_MAP.get(activeElement);
        const activeState = activeId ? EDITABLE_VALUE_STATE_BY_ID.get(activeId) : null;
        if (activeState?.isMasked) {
          scheduleEditableOverlaySync();
        }
      }
    },
    true
  );
}

function initializeViewportListeners() {
  const syncOverlays = () => {
    scheduleEditableOverlaySync();
  };

  window.addEventListener(
    "scroll",
    () => {
      syncOverlays();
      scheduleScrollVisibilityRefresh();
    },
    true
  );

  window.addEventListener("resize", () => {
    syncOverlays();
    scheduleScrollVisibilityRefresh();
    schedulePipeline("visibility");
  });

  if (window.visualViewport) {
    window.visualViewport.addEventListener(
      "scroll",
      () => {
        syncOverlays();
        scheduleScrollVisibilityRefresh();
      },
      { passive: true }
    );
    window.visualViewport.addEventListener(
      "resize",
      () => {
        syncOverlays();
        scheduleScrollVisibilityRefresh();
      },
      { passive: true }
    );
  }
}

// self-test helpers are loaded from content-self-test.js

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!isExtensionContextAvailable()) {
    sendResponse({ ok: false, reason: "EXTENSION_CONTEXT_INVALIDATED", errorCode: "EXTENSION_CONTEXT_INVALIDATED" });
    return false;
  }

  if (message?.type === "RUN_PIPELINE" || message?.type === "RUN_FILTER") {
    executePipeline(message.reason || "manual").then((result) => {
      sendResponse(result);
    });
    return true;
  }

  if (message?.type === "RUN_SELF_TEST") {
    runFilterLabSelfTest().then(sendResponse);
    return true;
  }

  return false;
});

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== "sync") return;
  if (!changes?.settings) return;
  updateCachedSettings(changes.settings.newValue || {});
  schedulePipeline("settings-updated");
});

async function bootstrap() {
  if (bootstrapStarted || extensionContextInvalidated || isUnsupportedPage()) return;
  if (!document.body || !document.documentElement) return;

  bootstrapStarted = true;
  initializeVisibilityObserver();
  initializeInputListeners();
  initializeViewportListeners();
  initializeNavigationListeners();
  initializeLabSelfTestListeners();
  registerTextNodesInTree(document.body, {
    markDirty: true,
    onlyVisible: true,
    limit: MAX_INITIAL_TEXT_NODES
  });
  scheduleInitialEditablePass();
  scheduleStartupFollowupPipelines();
  initializeObserver();

  window.requestAnimationFrame(() => {
    executePipeline("initial-load").catch((error) => {
      if (!handleExtensionContextError(error)) {
        console.error("[청마루] initial-load pipeline error", error);
      }
    });
  });
}

function scheduleBootstrapWhenReady() {
  if (bootstrapStarted || extensionContextInvalidated) return;

  if (document.body && document.documentElement) {
    bootstrap().catch((error) => {
      if (!handleExtensionContextError(error)) {
        console.error("[청마루] bootstrap error", error);
      }
    });
    return;
  }

  if (bootstrapRetryTimerId) {
    return;
  }

  bootstrapRetryTimerId = window.setTimeout(() => {
    bootstrapRetryTimerId = null;
    scheduleBootstrapWhenReady();
  }, 16);
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", () => {
    scheduleBootstrapWhenReady();
  });
  scheduleBootstrapWhenReady();
} else {
  scheduleBootstrapWhenReady();
}
