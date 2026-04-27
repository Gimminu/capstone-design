function isEditableTooltipTitleFromChungmaru(value) {
  if (typeof isLikelyChungmaruTooltipTitle === "function") {
    return isLikelyChungmaruTooltipTitle(value);
  }

  const text = String(value || "").trim();
  return Boolean(text && /(?:공격|모욕|혐오|스팸|유해|콘텐츠|\d{1,3}%)/.test(text));
}

function restoreEditableValueState(state) {
  if (!state?.element) return;
  if (typeof suppressMutationFeedback === "function") {
    suppressMutationFeedback(140);
  }

  if (state.originalTitle && !isEditableTooltipTitleFromChungmaru(state.originalTitle)) {
    state.element.title = state.originalTitle;
  } else {
    state.element.removeAttribute("title");
  }

  if (state.overlayRoot?.isConnected) {
    state.overlayRoot.remove();
  }

  state.overlayRoot = null;
  state.overlayContent = null;
  state.overlayMode = "";
  if (state.overlayHostPositionPatched && state.overlayHost instanceof HTMLElement) {
    state.overlayHost.style.position = state.overlayHostOriginalPosition || "";
  }
  state.overlayHost = null;
  state.overlayHostPositionPatched = false;
  state.overlayHostOriginalPosition = "";
  state.maskedText = "";
  state.maskedSpans = [];
  state.overlayTooltip = "";
  state.overlayRenderKey = "";
  state.overlayLayoutKey = "";
  state.overlayTextColor = "";
  state.overlayTextFillColor = "";
  state.element.style.color = state.originalColor || "";
  state.element.style.webkitTextFillColor = state.originalWebkitTextFillColor || "";
  state.element.style.caretColor = state.originalCaretColor || "";
  state.element.style.textShadow = state.originalTextShadow || "";
  state.element.style.filter = state.originalFilter || "";
  state.element.style.opacity = state.originalOpacity || "";
  state.element.style.webkitTextSecurity = state.originalWebkitTextSecurity || "";
  state.element.style.textSecurity = state.originalTextSecurity || "";
  state.element.classList.remove("shieldtext-editable-source-concealed");
  state.element.classList.remove("shieldtext-editable-hard-concealed");
  delete state.element.dataset.shieldtextHardConceal;
  state.nativeMaskApplied = false;
  MASKED_EDITABLE_STATE_IDS.delete(state.nodeId);

  state.isMasked = false;
  state.isPending = false;
  state.lastDecisionKey = "";
}

function isSingleLineEditableElement(element) {
  if (element instanceof HTMLInputElement) {
    const inputType = String(element.type || "text").toLowerCase();
    return ["text", "search", ""].includes(inputType);
  }

  if (element instanceof HTMLTextAreaElement) {
    const rows = Number(element.rows || 0);
    if (rows > 1) {
      return false;
    }

    if (String(element.getAttribute("role") || "").toLowerCase() === "combobox") {
      return true;
    }

    return !normalizeText(element.value).includes("\n");
  }

  return false;
}

function shouldUseHardEditableConcealment(element) {
  if (!(element instanceof HTMLTextAreaElement)) {
    return false;
  }

  const hostname = String(location.hostname || "").toLowerCase();
  if (!/(^|\.)google\./i.test(hostname)) {
    return false;
  }

  const role = String(element.getAttribute("role") || "").toLowerCase();
  const name = String(element.getAttribute("name") || "").toLowerCase();
  return role === "combobox" || name === "q" || element.id === "APjFqb";
}

function isTransparentCssColor(value) {
  const text = String(value || "").trim().toLowerCase();
  if (!text || text === "transparent") return true;
  return (
    /^rgba\([^)]*,\s*0(?:\.0+)?\)$/.test(text) ||
    /^rgb\([^)]*\/\s*0(?:\.0+)?\)$/.test(text)
  );
}

function rememberEditableOverlayTextColor(state, computedStyle) {
  if (!state || !computedStyle) return;
  if (!isTransparentCssColor(computedStyle.color)) {
    state.overlayTextColor = computedStyle.color;
  }
  if (!isTransparentCssColor(computedStyle.webkitTextFillColor)) {
    state.overlayTextFillColor = computedStyle.webkitTextFillColor;
  }
}

function measureEditableTextWidthPx(text, computedStyle) {
  const sourceText = String(text || "");
  if (!sourceText) return 0;

  const canvas =
    measureEditableTextWidthPx.canvas ||
    (measureEditableTextWidthPx.canvas = document.createElement("canvas"));
  const context = canvas.getContext("2d");
  if (!context) {
    return sourceText.length * 12;
  }

  context.font = computedStyle?.font || [
    computedStyle?.fontStyle,
    computedStyle?.fontVariant,
    computedStyle?.fontWeight,
    computedStyle?.fontSize,
    computedStyle?.fontFamily
  ].filter(Boolean).join(" ");

  let width = context.measureText(sourceText).width;
  const letterSpacing = parseFloat(computedStyle?.letterSpacing || "0");
  if (Number.isFinite(letterSpacing) && letterSpacing > 0 && sourceText.length > 1) {
    width += letterSpacing * (sourceText.length - 1);
  }

  return Math.ceil(width);
}

function getEditableFullSpanMaskWidthPx(element, text) {
  if (!(element instanceof Element)) return 0;
  const computedStyle = window.getComputedStyle(element);
  const measuredWidth = measureEditableTextWidthPx(text, computedStyle);
  const fontSize = parseFloat(computedStyle.fontSize || "16");
  const guardPx = Number.isFinite(fontSize)
    ? Math.max(6, Math.round(fontSize * (shouldUseHardEditableConcealment(element) ? 0.72 : 0.36)))
    : 8;
  return Math.max(8, measuredWidth + guardPx);
}

function getEditableFullSpanMaskHeightPx(element) {
  if (!(element instanceof Element)) return 0;

  const computedStyle = window.getComputedStyle(element);
  const rect = element.getBoundingClientRect();
  const fontSize = parseFloat(computedStyle.fontSize || "16");
  const parsedLineHeight = parseFloat(computedStyle.lineHeight || "");
  const lineHeight = Number.isFinite(parsedLineHeight)
    ? parsedLineHeight
    : (Number.isFinite(fontSize) ? fontSize * 1.28 : 22);
  const verticalPadding =
    (parseFloat(computedStyle.paddingTop || "0") || 0) +
    (parseFloat(computedStyle.paddingBottom || "0") || 0);
  const availableHeight = Math.max(0, rect.height - Math.max(0, verticalPadding * 0.35));
  const targetHeight = Math.max(lineHeight * 1.08, Number.isFinite(fontSize) ? fontSize * 1.42 : lineHeight);

  if (isSingleLineEditableElement(element) && rect.height > 0) {
    return Math.round(Math.max(availableHeight, targetHeight, rect.height));
  }

  if (availableHeight > 0) {
    return Math.round(Math.min(availableHeight, targetHeight));
  }

  return Math.round(targetHeight);
}

function concealEditableSourceText(state) {
  if (!state?.element) return;
  if (typeof suppressMutationFeedback === "function") {
    suppressMutationFeedback(140);
  }

  const computedStyle = window.getComputedStyle(state.element);
  rememberEditableOverlayTextColor(state, computedStyle);
  const caretColor = computedStyle.caretColor && computedStyle.caretColor !== "auto"
    ? computedStyle.caretColor
    : computedStyle.color;
  state.element.style.webkitTextSecurity = state.originalWebkitTextSecurity || "";
  state.element.style.textSecurity = state.originalTextSecurity || "";
  state.element.style.setProperty("color", "transparent", "important");
  state.element.style.setProperty("-webkit-text-fill-color", "transparent", "important");
  state.element.style.setProperty("caret-color", caretColor, "important");
  state.element.style.setProperty("text-shadow", "none", "important");
  state.element.removeAttribute("title");
  if (shouldUseHardEditableConcealment(state.element)) {
    state.element.style.filter = state.originalFilter || "";
    state.element.style.setProperty("opacity", "0", "important");
    state.element.classList.add("shieldtext-editable-hard-concealed");
    state.element.dataset.shieldtextHardConceal = "true";
  } else {
    state.element.style.filter = state.originalFilter || "";
    state.element.style.opacity = state.originalOpacity || "";
    state.element.classList.remove("shieldtext-editable-hard-concealed");
    delete state.element.dataset.shieldtextHardConceal;
  }
  state.element.classList.add("shieldtext-editable-source-concealed");
  state.nativeMaskApplied = false;
}

function applyNativeFullEditableMask(state) {
  if (!state?.element) return false;
  if (typeof suppressMutationFeedback === "function") {
    suppressMutationFeedback(140);
  }

  // Chrome applies text-security reliably to text/search inputs, but not to
  // textarea-based comboboxes such as Google Search. Treat textarea as overlay
  // only; otherwise state says "masked" while the original value remains shown.
  if (!(state.element instanceof HTMLInputElement)) return false;
  if (!isSingleLineEditableElement(state.element)) return false;

  if (state.overlayRoot?.isConnected) {
    state.overlayRoot.remove();
  }

  state.overlayRoot = null;
  state.overlayContent = null;
  state.overlayMode = "native-full-mask";
  state.overlayRenderKey = "";
  state.overlayLayoutKey = "";
  state.element.style.webkitTextSecurity = "disc";
  state.element.style.textSecurity = "disc";
  state.element.style.color = state.originalColor || "";
  state.element.style.webkitTextFillColor = state.originalWebkitTextFillColor || "";
  state.element.style.textShadow = state.originalTextShadow || "";
  state.element.style.opacity = state.originalOpacity || "";
  state.element.classList.remove("shieldtext-editable-source-concealed");
  state.element.classList.remove("shieldtext-editable-hard-concealed");
  delete state.element.dataset.shieldtextHardConceal;
  state.element.removeAttribute("title");
  state.nativeMaskApplied = true;
  MASKED_EDITABLE_STATE_IDS.add(state.nodeId);
  return true;
}

function getEditableOverlayHost(element) {
  // Fixed overlays are more stable for search inputs/combobox textareas whose
  // parent layout can move independently during browser or SPA UI transitions.
  return document.body || document.documentElement;
}

function ensureEditableOverlayHost(state) {
  const host = getEditableOverlayHost(state?.element);
  if (!(host instanceof HTMLElement)) {
    return document.body || document.documentElement;
  }

  if (state.overlayHost !== host) {
    if (state.overlayHostPositionPatched && state.overlayHost instanceof HTMLElement) {
      state.overlayHost.style.position = state.overlayHostOriginalPosition || "";
    }
    state.overlayHost = host;
    state.overlayHostPositionPatched = false;
    state.overlayHostOriginalPosition = "";
  }

  if (host !== document.body && host !== document.documentElement) {
    const computedPosition = window.getComputedStyle(host).position;
    if (computedPosition === "static" && !state.overlayHostPositionPatched) {
      state.overlayHostOriginalPosition = host.style.position || "";
      host.style.position = "relative";
      state.overlayHostPositionPatched = true;
    }
  }

  return host;
}

function ensureEditableOverlay(state) {
  const overlayHost = ensureEditableOverlayHost(state);

  if (state.overlayRoot?.isConnected && state.overlayContent?.isConnected) {
    if (
      overlayHost instanceof Node &&
      state.overlayRoot.parentNode !== overlayHost
    ) {
      overlayHost.appendChild(state.overlayRoot);
    }
    return state.overlayRoot;
  }

  const overlayRoot = document.createElement("div");
  overlayRoot.className = "shieldtext-editable-overlay";
  overlayRoot.setAttribute("aria-hidden", "true");
  overlayRoot.dataset.shieldtextOverlay = "true";
  overlayRoot.style.setProperty("z-index", "2147483647", "important");
  overlayRoot.style.setProperty("pointer-events", "none", "important");

  const overlayContent = document.createElement("div");
  overlayContent.className = "shieldtext-editable-overlay-content";
  overlayRoot.appendChild(overlayContent);
  (overlayHost || document.body || document.documentElement).appendChild(overlayRoot);

  state.overlayRoot = overlayRoot;
  state.overlayContent = overlayContent;
  return overlayRoot;
}

function isEditableOverlayLayoutVisible(element) {
  if (!(element instanceof Element)) return false;

  const style = window.getComputedStyle(element);
  if (style.display === "none" || style.visibility === "hidden") {
    return false;
  }

  const rect = element.getBoundingClientRect();
  return rect.width > 0 && rect.height > 0;
}

function syncEditableOverlayLayout(state) {
  if (!state?.overlayRoot || !state.overlayContent || !state.element?.isConnected) return;

  const element = state.element;
  const overlayHost = ensureEditableOverlayHost(state);
  const rect = element.getBoundingClientRect();
  if (!isEditableOverlayLayoutVisible(element) || !isElementNearViewport(rect)) {
    state.overlayRoot.style.display = "none";
    return;
  }
  if (state.overlayMode === "full-overlay") {
    concealEditableSourceText(state);
  }

  const style = window.getComputedStyle(element);
  const overlayTextColor = isTransparentCssColor(style.color)
    ? (state.overlayTextColor || "")
    : style.color;
  const overlayTextFillColor = isTransparentCssColor(style.webkitTextFillColor)
    ? (state.overlayTextFillColor || overlayTextColor)
    : style.webkitTextFillColor;
  const overlayRoot = state.overlayRoot;
  const overlayContent = state.overlayContent;
  const hostRect =
    overlayHost === document.body || overlayHost === document.documentElement
      ? { left: 0, top: 0 }
      : overlayHost instanceof Element
      ? overlayHost.getBoundingClientRect()
      : { left: 0, top: 0 };
  const isSingleLineEditable = isSingleLineEditableElement(element);
  const computedLineHeight = style.lineHeight || "normal";
  const overlayWidth = Math.max(rect.width, isSingleLineEditable ? rect.width : element.scrollWidth || 0);
  const overlayHeight = Math.max(rect.height, isSingleLineEditable ? rect.height : element.scrollHeight || 0);
  const layoutKey = JSON.stringify({
    mode: state.overlayMode || "",
    left: Math.round(rect.left - hostRect.left),
    top: Math.round(rect.top - hostRect.top),
    width: Math.round(rect.width),
    height: Math.round(rect.height),
    overlayWidth: Math.round(overlayWidth),
    overlayHeight: Math.round(overlayHeight),
    scrollLeft: Math.round(Number(element.scrollLeft || 0)),
    scrollTop: Math.round(Number(element.scrollTop || 0))
  });

  overlayRoot.style.display = "block";
  if (state.overlayLayoutKey === layoutKey) {
    overlayLayoutReuseCount += 1;
    return;
  }

  overlayLayoutRebuildCount += 1;
  state.overlayLayoutKey = layoutKey;
  overlayRoot.style.position =
    overlayHost === document.body || overlayHost === document.documentElement
      ? "fixed"
      : "absolute";
  overlayRoot.style.setProperty("z-index", "2147483647", "important");
  overlayRoot.style.setProperty("pointer-events", "none", "important");
  overlayRoot.style.isolation = "isolate";
  overlayRoot.style.left = `${Math.round(rect.left - hostRect.left)}px`;
  overlayRoot.style.top = `${Math.round(rect.top - hostRect.top)}px`;
  overlayRoot.style.width = `${Math.round(rect.width)}px`;
  overlayRoot.style.height = `${Math.round(rect.height)}px`;
  overlayRoot.style.padding = "0";
  overlayRoot.style.border = "0";
  overlayRoot.style.boxSizing = style.boxSizing;
  overlayRoot.style.borderRadius = style.borderRadius;
  overlayRoot.style.overflow = "hidden";

  overlayContent.style.display = isSingleLineEditable ? "flex" : "block";
  overlayContent.style.position = "absolute";
  overlayContent.style.left = "0";
  overlayContent.style.top = "0";
  overlayContent.style.padding = style.padding;
  overlayContent.style.border = style.border;
  overlayContent.style.borderRadius = style.borderRadius;
  overlayContent.style.boxSizing = style.boxSizing;
  overlayContent.style.font = style.font;
  overlayContent.style.fontFamily = style.fontFamily;
  overlayContent.style.fontSize = style.fontSize;
  overlayContent.style.fontWeight = style.fontWeight;
  overlayContent.style.lineHeight = computedLineHeight;
  overlayContent.style.letterSpacing = style.letterSpacing;
  overlayContent.style.wordSpacing = style.wordSpacing;
  overlayContent.style.fontKerning = style.fontKerning;
  overlayContent.style.fontVariant = style.fontVariant;
  overlayContent.style.fontVariantLigatures = style.fontVariantLigatures;
  overlayContent.style.fontVariantNumeric = style.fontVariantNumeric;
  overlayContent.style.fontFeatureSettings = style.fontFeatureSettings;
  overlayContent.style.textRendering = style.textRendering;
  overlayContent.style.textAlign = style.textAlign;
  overlayContent.style.textTransform = style.textTransform;
  overlayContent.style.textIndent = style.textIndent;
  overlayContent.style.textShadow = "none";
  overlayContent.style.direction = style.direction;
  overlayContent.style.writingMode = style.writingMode;
  overlayContent.style.color = overlayTextColor || style.color;
  overlayContent.style.webkitTextFillColor = overlayTextFillColor || overlayTextColor || style.color;
  overlayContent.style.alignItems = isSingleLineEditable ? "center" : "";
  overlayContent.style.flexWrap = "nowrap";
  overlayContent.style.whiteSpace = isSingleLineEditable
    ? "pre"
    : element instanceof HTMLTextAreaElement
      ? "pre-wrap"
      : "pre";

  if (isSingleLineEditable) {
    overlayContent.style.height = `${Math.round(rect.height)}px`;
    overlayContent.style.minHeight = `${Math.round(rect.height)}px`;
  }

  overlayContent.style.width = `${Math.round(overlayWidth)}px`;
  overlayContent.style.minWidth = `${Math.round(overlayWidth)}px`;
  overlayContent.style.minHeight = `${Math.round(overlayHeight)}px`;
  overlayContent.style.transform = `translate3d(${-Math.round(Number(element.scrollLeft || 0))}px, ${-Math.round(Number(element.scrollTop || 0))}px, 0)`;
}

function renderEditableOverlay(state, text, spans, settings, tooltip) {
  if (typeof suppressMutationFeedback === "function") {
    suppressMutationFeedback(180);
  }

  ensureEditableOverlay(state);
  state.overlayMode = "full-overlay";
  state.maskedText = text;
  state.maskedSpans = spans;
  state.overlayTooltip = tooltip;
  if (doSpansCoverFullText(spans, text)) {
    state.overlayRoot.dataset.shieldtextFullSpan = "true";
  } else {
    delete state.overlayRoot.dataset.shieldtextFullSpan;
  }
  concealEditableSourceText(state);
  syncEditableOverlayLayout(state);

  if (!state.overlayContent) return;

  const renderKey = JSON.stringify({
    text,
    spans,
    interventionMode: settings?.interventionMode || "mask",
    fullSpanMaskWidthPx: doSpansCoverFullText(spans, text)
      ? getEditableFullSpanMaskWidthPx(state.element, text)
      : 0,
    fullSpanMaskHeightPx: doSpansCoverFullText(spans, text)
      ? getEditableFullSpanMaskHeightPx(state.element)
      : 0
  });
  if (state.overlayRenderKey === renderKey) {
    state.overlayRoot.removeAttribute("title");
    state.element.removeAttribute("title");
    MASKED_EDITABLE_STATE_IDS.add(state.nodeId);
    return;
  }

  state.overlayContent.textContent = "";
  const fragment = document.createDocumentFragment();
  let cursor = 0;

  for (const span of spans) {
    if (span.start > cursor) {
      const safeSegment = document.createElement("span");
      safeSegment.className = "shieldtext-editable-visible";
      safeSegment.textContent = text.slice(cursor, span.start);
      fragment.appendChild(safeSegment);
    }

    const mask = document.createElement("span");
    mask.className = settings?.interventionMode === "hide"
      ? "shieldtext-editable-hide"
      : "shieldtext-editable-mask";
    if (doSpansCoverFullText(spans, text)) {
      const fullSpanWidthPx = getEditableFullSpanMaskWidthPx(state.element, text);
      const fullSpanHeightPx = getEditableFullSpanMaskHeightPx(state.element);
      mask.style.display = "inline-flex";
      mask.style.alignItems = "center";
      mask.style.alignSelf = "center";
      mask.style.width = `${fullSpanWidthPx}px`;
      mask.style.minWidth = `${fullSpanWidthPx}px`;
      if (fullSpanHeightPx > 0) {
        mask.style.height = `${fullSpanHeightPx}px`;
        mask.style.minHeight = `${fullSpanHeightPx}px`;
      }
    }
    mask.style.setProperty("color", "transparent", "important");
    mask.style.setProperty("-webkit-text-fill-color", "transparent", "important");
    mask.style.setProperty("text-shadow", "none", "important");
    if (!doSpansCoverFullText(spans, text)) {
      const hiddenText = document.createElement("span");
      hiddenText.className = "shieldtext-hidden-mask-text";
      hiddenText.textContent = text.slice(span.start, span.end);
      hiddenText.style.setProperty("visibility", "hidden", "important");
      hiddenText.style.setProperty("opacity", "0", "important");
      hiddenText.style.setProperty("color", "transparent", "important");
      hiddenText.style.setProperty("-webkit-text-fill-color", "transparent", "important");
      hiddenText.style.setProperty("text-shadow", "none", "important");
      mask.appendChild(hiddenText);
    }
    fragment.appendChild(mask);

    cursor = span.end;
  }

  if (cursor < text.length) {
    const tail = document.createElement("span");
    tail.className = "shieldtext-editable-visible";
    tail.textContent = text.slice(cursor);
    fragment.appendChild(tail);
  }

  state.overlayContent.appendChild(fragment);
  state.overlayRenderKey = renderKey;
  state.overlayRoot.removeAttribute("title");
  state.element.removeAttribute("title");
  MASKED_EDITABLE_STATE_IDS.add(state.nodeId);
  scheduleEditableOverlaySync(2);
}

function mapEditableMaskSpansToCurrentText(state, currentText) {
  const previousText = String(state?.maskedText || "");
  const nextText = String(currentText || "");
  if (!previousText || !nextText || previousText === nextText) {
    return normalizeEvidenceSpans(state?.maskedSpans || [], nextText);
  }

  const previousSpans = normalizeEvidenceSpans(state?.maskedSpans || [], previousText);
  const nextSpans = [];
  let searchFrom = 0;

  for (const span of previousSpans) {
    const maskedSegment = previousText.slice(span.start, span.end);
    if (!maskedSegment) {
      continue;
    }

    const nextIndex = nextText.indexOf(maskedSegment, searchFrom);
    if (nextIndex < 0) {
      return [];
    }

    nextSpans.push({
      start: nextIndex,
      end: nextIndex + maskedSegment.length,
      score: span.score,
      text: maskedSegment
    });
    searchFrom = nextIndex + maskedSegment.length;
  }

  return normalizeEvidenceSpans(nextSpans, nextText);
}

function carryForwardEditableMask(state, currentText, settings) {
  if (!state?.isMasked || !state.element?.isConnected) {
    return false;
  }

  const nextText = String(currentText || "");
  const mappedSpans = mapEditableMaskSpansToCurrentText(state, nextText);
  if (mappedSpans.length === 0) {
    return false;
  }

  renderEditableOverlay(
    state,
    nextText,
    mappedSpans,
    settings || (typeof cachedSettings === "object" ? cachedSettings : null) || DEFAULT_SETTINGS,
    state.overlayTooltip || ""
  );
  return true;
}

function doSpansCoverFullText(spans, text) {
  if (!Array.isArray(spans) || spans.length === 0) {
    return false;
  }

  if (spans.length !== 1) {
    return false;
  }

  const fullLength = String(text || "").length;
  const span = spans[0];
  return Number(span.start) <= 0 && Number(span.end) >= fullLength;
}

function renderEditableValueOutcome(candidate, outcome, settings) {
  const state = candidate?.state;
  if (!state?.element) return;

  if (!outcome?.blocked) {
    restoreEditableValueState(state);
    return;
  }

  const spans = normalizeEvidenceSpans(outcome.spans, candidate.text);
  if (spans.length === 0) {
    restoreEditableValueState(state);
    return;
  }

  const tooltip = buildMaskTooltip(outcome.categories, outcome.reasons, settings);
  // Keep editable masking on one rendering path. Native text-security is fast
  // but page/browser dependent, and it can expose the original value during
  // Google Search textarea/input transitions.
  const shouldUseNativeFullMask = false;
  const decisionKey = JSON.stringify({
    text: candidate.text,
    categories: outcome.categories,
    interventionMode: settings?.interventionMode || "mask",
    tooltip,
    spans,
    nativeMask: shouldUseNativeFullMask
  });
  if (shouldUseNativeFullMask) {
    state.isMasked = true;
    state.isPending = false;
    state.lastDecisionKey = decisionKey;
    return;
  }

  if (decisionKey === state.lastDecisionKey) {
    renderEditableOverlay(state, candidate.text, spans, settings, tooltip);
    return;
  }

  renderEditableOverlay(state, candidate.text, spans, settings, tooltip);
  state.isMasked = true;
  state.isPending = false;
  state.lastDecisionKey = decisionKey;
}

function syncAllMaskedEditableOverlays() {
  for (const nodeId of MASKED_EDITABLE_STATE_IDS) {
    const state = EDITABLE_VALUE_STATE_BY_ID.get(nodeId);
    if (!state?.isMasked || !state.element?.isConnected) {
      if (state?.overlayRoot?.isConnected) {
        state.overlayRoot.remove();
      }
      MASKED_EDITABLE_STATE_IDS.delete(nodeId);
      continue;
    }

    syncEditableOverlayLayout(state);
  }
}

function scheduleEditableOverlaySync(frames = 1) {
  pendingEditableOverlaySyncFrames = Math.max(
    pendingEditableOverlaySyncFrames,
    Math.min(2, Math.max(0, Number(frames || 0)))
  );

  if (extensionContextInvalidated || overlaySyncFrameId) {
    return;
  }

  const runSync = () => {
    if (extensionContextInvalidated) {
      overlaySyncFrameId = null;
      pendingEditableOverlaySyncFrames = 0;
      return;
    }

    overlaySyncFrameId = window.requestAnimationFrame(() => {
      overlaySyncFrameId = null;
      syncAllMaskedEditableOverlays();
      if (pendingEditableOverlaySyncFrames > 0) {
        pendingEditableOverlaySyncFrames -= 1;
      }

      if (pendingEditableOverlaySyncFrames > 0) {
        runSync();
      }
    });
  };

  runSync();
}
