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

const RULES = {
  abuse: ["꺼져", "닥쳐", "죽어", "지랄"],
  hate: ["혐오", "역겹", "벌레같", "증오"],
  insult: ["멍청", "한심", "바보", "병신"],
  spam: ["무료", "클릭", "당첨", "광고", "구독"]
};

const CATEGORY_LABELS = {
  abuse: "유해",
  hate: "혐오",
  insult: "모욕",
  spam: "스팸",
  custom: "사용자"
};

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

const PIPELINE_DEBOUNCE_MS = 250;
const MAX_CANDIDATES = 3000;

const ELEMENT_ID_MAP = new WeakMap();
const HIDDEN_BY_ID = new Map();

let nextElementId = 1;
let observer = null;
let debounceTimerId = null;
let isPipelineRunning = false;
let queuedReason = null;
let ignoreMutationsUntil = 0;

function normalizeText(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function isUnsupportedPage() {
  if (!document.body) return true;
  if (!location || !location.href) return true;
  if (location.protocol === "chrome:" || location.protocol === "chrome-extension:") return true;
  if ((document.contentType || "").toLowerCase().includes("pdf")) return true;
  return false;
}

function parseCsvList(value) {
  return String(value || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function parseCsvSetLower(value) {
  return new Set(parseCsvList(value).map((item) => item.toLowerCase()));
}

function normalizeSensitivity(value) {
  const num = Number(value);
  if (Number.isNaN(num)) return DEFAULT_SETTINGS.sensitivity;
  return Math.max(0, Math.min(100, num));
}

function getMinKeywordLengthBySensitivity(sensitivity) {
  if (sensitivity >= 50) return 2;
  if (sensitivity >= 20) return 3;
  return 4;
}

function getRequiredHitCountBySensitivity(sensitivity) {
  if (sensitivity >= 70) return 1;
  if (sensitivity >= 45) return 2;
  return 3;
}

function getElementId(element) {
  let id = ELEMENT_ID_MAP.get(element);
  if (!id) {
    id = `node-${nextElementId++}`;
    ELEMENT_ID_MAP.set(element, id);
  }
  return id;
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

async function loadSettings() {
  const { settings } = await chrome.storage.sync.get("settings");
  return getMergedSettings(settings || {});
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

function isEditableElement(element) {
  if (!(element instanceof Element)) return false;
  if (element.isContentEditable) return true;
  if (element.closest("[contenteditable='true']")) return true;

  const tagName = element.tagName;
  return tagName === "INPUT" || tagName === "TEXTAREA" || tagName === "SELECT";
}

function isSkippableElement(element) {
  if (!(element instanceof Element)) return true;
  if (SKIP_TAGS.has(element.tagName)) return true;
  if (isEditableElement(element)) return true;
  if (element.closest("form")) return true;
  if (element.closest("pre, code, textarea, input, button, select")) return true;
  if (element.getAttribute("role") === "button") return true;
  if (element.getAttribute("role") === "textbox") return true;
  return false;
}

function hasVisibleTextChild(element) {
  const children = element.children;
  for (let idx = 0; idx < children.length; idx += 1) {
    const child = children[idx];
    if (!isElementVisible(child)) continue;
    if (isSkippableElement(child)) continue;

    const childText = normalizeText(child.textContent);
    const childDesc = normalizeText(
      child.getAttribute("aria-label") || child.getAttribute("alt") || child.getAttribute("title")
    );

    if (childText.length > 0 || childDesc.length > 0) {
      return true;
    }
  }

  return false;
}

function resolveContentDescription(element, normalizedText) {
  const ariaLabel = normalizeText(element.getAttribute("aria-label"));
  if (ariaLabel) return ariaLabel;

  const altText = normalizeText(element.getAttribute("alt"));
  if (altText) return altText;

  const titleText = normalizeText(element.getAttribute("title"));
  if (titleText) return titleText;

  return normalizedText;
}

function collectCandidates() {
  if (!document.body) return [];

  const candidates = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);

  while (walker.nextNode()) {
    if (candidates.length >= MAX_CANDIDATES) break;

    const element = walker.currentNode;
    if (!(element instanceof Element)) continue;
    if (isSkippableElement(element)) continue;
    if (!isElementVisible(element)) continue;

    const normalizedText = normalizeText(element.textContent);
    const contentDescription = resolveContentDescription(element, normalizedText);

    if (contentDescription.length < 2) continue;
    if (hasVisibleTextChild(element)) continue;

    const rect = element.getBoundingClientRect();
    const nodeId = getElementId(element);
    const className =
      typeof element.className === "string" && element.className.trim().length > 0
        ? element.className.trim()
        : element.tagName;

    candidates.push({
      nodeId,
      element,
      packageName: `web::${location.hostname || "unknown"}`,
      className,
      text: normalizedText,
      displayText: contentDescription,
      contentDescription,
      isVisibleToUser: true,
      top: Math.round(rect.top + window.scrollY),
      bottom: Math.round(rect.bottom + window.scrollY),
      left: Math.round(rect.left + window.scrollX),
      right: Math.round(rect.right + window.scrollX)
    });
  }

  return candidates;
}

function buildPayload(candidates) {
  const packageName = `web::${location.hostname || "unknown"}`;
  const rawTextNodes = candidates.map((item) => ({
    nodeId: item.nodeId,
    approxTop: item.top,
    top: item.top,
    bottom: item.bottom,
    left: item.left,
    right: item.right,
    className: item.className,
    packageName: item.packageName,
    isVisibleToUser: item.isVisibleToUser,
    text: item.text,
    displayText: item.displayText,
    contentDescription: item.contentDescription
  }));

  return {
    commentCandidates: [],
    packageName,
    rawTextNodes,
    timestamp: Date.now()
  };
}

function buildActiveKeywords(settings) {
  const sensitivity = normalizeSensitivity(settings.sensitivity);
  const minKeywordLength = getMinKeywordLengthBySensitivity(sensitivity);
  const allowSet = parseCsvSetLower(settings.customAllowWords);
  const keywords = [];

  for (const [category, words] of Object.entries(RULES)) {
    if (!settings.categories?.[category]) continue;

    for (const rawWord of words) {
      const keyword = normalizeText(rawWord).toLowerCase();
      if (keyword.length < minKeywordLength) continue;
      if (allowSet.has(keyword)) continue;
      keywords.push({ category, keyword, rawKeyword: rawWord });
    }
  }

  for (const customWord of parseCsvList(settings.customBlockWords)) {
    const keyword = normalizeText(customWord).toLowerCase();
    if (keyword.length < minKeywordLength) continue;
    if (allowSet.has(keyword)) continue;
    keywords.push({ category: "custom", keyword, rawKeyword: customWord });
  }

  const dedup = new Map();
  for (const item of keywords) {
    const key = `${item.category}::${item.keyword}`;
    if (!dedup.has(key)) dedup.set(key, item);
  }

  return [...dedup.values()];
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

function dummyAnalyze(payload, settings) {
  const sensitivity = normalizeSensitivity(settings.sensitivity);
  const requiredHitCount = getRequiredHitCountBySensitivity(sensitivity);
  const blockedNodeIdSet = new Set();
  const matchedKeywordSet = new Set();
  const nodeCategoryMap = {};
  const categoryHits = emptyCategoryHits();
  const activeKeywords = buildActiveKeywords(settings);

  for (const node of payload.rawTextNodes) {
    const normalizedContent = normalizeText(node.contentDescription).toLowerCase();
    if (!normalizedContent) continue;

    let nodeHitCount = 0;
    const localHits = [];
    const nodeCategorySet = new Set();

    for (const keywordMeta of activeKeywords) {
      if (!normalizedContent.includes(keywordMeta.keyword)) continue;

      const splitCount = normalizedContent.split(keywordMeta.keyword).length - 1;
      const hitCount = Math.max(1, splitCount);
      nodeHitCount += hitCount;
      localHits.push({ ...keywordMeta, hitCount });
      nodeCategorySet.add(keywordMeta.category);
    }

    if (nodeHitCount < requiredHitCount) continue;

    blockedNodeIdSet.add(node.nodeId);
    nodeCategoryMap[node.nodeId] = [...nodeCategorySet];

    for (const localHit of localHits) {
      matchedKeywordSet.add(localHit.rawKeyword);
      categoryHits[localHit.category] = Number(categoryHits[localHit.category] || 0) + localHit.hitCount;
    }
  }

  return {
    blockedNodeIds: [...blockedNodeIdSet],
    matchedKeywords: [...matchedKeywordSet],
    categoryHits,
    nodeCategoryMap,
    analyzedNodeCount: payload.rawTextNodes.length,
    blockedNodeCount: blockedNodeIdSet.size
  };
}

function getPrimaryCategory(categories) {
  if (!Array.isArray(categories) || categories.length === 0) return "abuse";
  return categories[0];
}

function getPlaceholderLabel(category) {
  return CATEGORY_LABELS[category] || CATEGORY_LABELS.abuse;
}

function hideElement(nodeId, element, categories, settings) {
  if (!(element instanceof Element)) return;

  const interventionMode = settings?.interventionMode === "hide" ? "hide" : "mask";
  const showReason = settings?.showReason !== false;
  const primaryCategory = getPrimaryCategory(categories);
  const label = showReason ? getPlaceholderLabel(primaryCategory) : "콘텐츠";
  const displayType = window.getComputedStyle(element).display;
  const placeholderText = interventionMode === "hide" ? `${label} 텍스트 숨김` : `${label} 텍스트 가림`;

  element.setAttribute("data-shieldtext-hidden", "true");
  element.setAttribute("data-shieldtext-mode", interventionMode);
  element.setAttribute("data-shieldtext-placeholder", placeholderText);
  element.setAttribute(
    "data-shieldtext-block-size",
    displayType.startsWith("inline") ? "inline" : "block"
  );
  element.classList.remove("shieldtext-hidden-element", "shieldtext-masked-element");

  if (interventionMode === "hide") {
    element.classList.add("shieldtext-hidden-element");
  } else {
    element.classList.add("shieldtext-masked-element");
  }

  HIDDEN_BY_ID.set(nodeId, element);
}

function restoreElement(nodeId, element) {
  if (!(element instanceof Element)) return;

  element.classList.remove("shieldtext-hidden-element", "shieldtext-masked-element");
  element.removeAttribute("data-shieldtext-hidden");
  element.removeAttribute("data-shieldtext-mode");
  element.removeAttribute("data-shieldtext-placeholder");
  element.removeAttribute("data-shieldtext-block-size");

  if (HIDDEN_BY_ID.get(nodeId) === element) {
    HIDDEN_BY_ID.delete(nodeId);
  }
}

function restoreAllHiddenElements() {
  for (const [nodeId, element] of HIDDEN_BY_ID.entries()) {
    restoreElement(nodeId, element);
  }
  HIDDEN_BY_ID.clear();
}

function applyDecision(candidates, decision, settings) {
  const blockedSet = new Set(decision.blockedNodeIds || []);
  ignoreMutationsUntil = Date.now() + 120;

  for (const [nodeId, element] of HIDDEN_BY_ID.entries()) {
    if (!element.isConnected || !blockedSet.has(nodeId)) {
      restoreElement(nodeId, element);
    }
  }

  for (const candidate of candidates) {
    if (!candidate.element.isConnected) continue;

    if (blockedSet.has(candidate.nodeId)) {
      hideElement(
        candidate.nodeId,
        candidate.element,
        decision.nodeCategoryMap?.[candidate.nodeId] || [],
        settings
      );
      continue;
    }

    restoreElement(candidate.nodeId, candidate.element);
  }
}

async function persistDebug(payload, decision, stats) {
  await chrome.storage.local.set({
    lastPayload: payload,
    lastDecision: decision,
    lastRunAt: Date.now(),
    lastStats: stats
  });

  console.info("[ShieldText] pipeline", {
    analyzedNodeCount: stats.analyzedNodeCount,
    blockedNodeCount: stats.blockedNodeCount,
    hostname: stats.hostname,
    runReason: stats.runReason
  });
}

async function executePipeline(runReason) {
  if (isUnsupportedPage()) {
    return { ok: false, reason: "UNSUPPORTED_PAGE" };
  }

  if (isPipelineRunning) {
    queuedReason = runReason;
    return { ok: true, queued: true };
  }

  isPipelineRunning = true;
  const startedAt = performance.now();

  try {
    const settings = await loadSettings();
    const hostname = location.hostname || "unknown";

    if (!settings.enabled) {
      ignoreMutationsUntil = Date.now() + 120;
      restoreAllHiddenElements();

      const payload = {
        commentCandidates: [],
        packageName: `web::${hostname}`,
        rawTextNodes: [],
        timestamp: Date.now()
      };

      const decision = {
        blockedNodeIds: [],
        matchedKeywords: [],
        categoryHits: emptyCategoryHits(),
        nodeCategoryMap: {},
        analyzedNodeCount: 0,
        blockedNodeCount: 0
      };

      const stats = {
        hostname,
        analyzedNodeCount: 0,
        blockedNodeCount: 0,
        matchedKeywordCount: 0,
        durationMs: Math.round(performance.now() - startedAt),
        runReason,
        enabled: false
      };

      await persistDebug(payload, decision, stats);
      return { ok: true, stats };
    }

    const candidates = collectCandidates();
    const payload = buildPayload(candidates);
    const decision = dummyAnalyze(payload, settings);
    applyDecision(candidates, decision, settings);

    const stats = {
      hostname,
      analyzedNodeCount: decision.analyzedNodeCount,
      blockedNodeCount: decision.blockedNodeCount,
      matchedKeywordCount: decision.matchedKeywords.length,
      durationMs: Math.round(performance.now() - startedAt),
      runReason,
      enabled: true
    };

    await persistDebug(payload, decision, stats);
    return { ok: true, stats };
  } catch (error) {
    console.error("[ShieldText] pipeline error", error);
    return { ok: false, reason: String(error) };
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
  if (isUnsupportedPage()) return;

  if (debounceTimerId) {
    window.clearTimeout(debounceTimerId);
  }

  debounceTimerId = window.setTimeout(() => {
    executePipeline(reason);
  }, PIPELINE_DEBOUNCE_MS);
}

function initializeObserver() {
  if (!document.documentElement) return;
  if (observer) observer.disconnect();

  observer = new MutationObserver((mutationList) => {
    if (!mutationList || mutationList.length === 0) return;
    if (Date.now() < ignoreMutationsUntil) return;

    schedulePipeline("mutation");
  });

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: ["aria-label", "title", "alt", "class", "style"]
  });
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "RUN_PIPELINE" || message?.type === "RUN_FILTER") {
    executePipeline(message.reason || "manual").then((result) => {
      sendResponse(result);
    });
    return true;
  }

  return false;
});

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== "sync") return;
  if (!changes?.settings) return;
  schedulePipeline("settings-updated");
});

async function bootstrap() {
  if (isUnsupportedPage()) return;
  await executePipeline("initial-load");
  initializeObserver();
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", () => {
    bootstrap().catch((error) => {
      console.error("[ShieldText] bootstrap error", error);
    });
  });
} else {
  bootstrap().catch((error) => {
    console.error("[ShieldText] bootstrap error", error);
  });
}
