function isEditableTooltipTitleFromChungmaru(value) {
  if (typeof isLikelyChungmaruTooltipTitle === "function") {
    return isLikelyChungmaruTooltipTitle(value);
  }

  const text = String(value || "").trim();
  return Boolean(text && /(?:공격|모욕|혐오|스팸|유해|콘텐츠|\d{1,3}%)/.test(text));
}

function restoreEditableValueState(state) {
  if (!state?.element) return;
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
  state.element.style.color = state.originalColor || "";
  state.element.style.webkitTextFillColor = state.originalWebkitTextFillColor || "";
  state.element.style.caretColor = state.originalCaretColor || "";
  state.element.style.textShadow = state.originalTextShadow || "";
  state.element.style.webkitTextSecurity = state.originalWebkitTextSecurity || "";
  state.element.style.textSecurity = state.originalTextSecurity || "";
  state.element.classList.remove("shieldtext-editable-source-concealed");
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

function concealEditableSourceText(state) {
  if (!state?.element) return;

  const computedStyle = window.getComputedStyle(state.element);
  const caretColor = computedStyle.caretColor && computedStyle.caretColor !== "auto"
    ? computedStyle.caretColor
    : computedStyle.color;
  state.element.style.webkitTextSecurity = state.originalWebkitTextSecurity || "";
  state.element.style.textSecurity = state.originalTextSecurity || "";
  state.element.style.setProperty("color", "transparent", "important");
  state.element.style.setProperty("-webkit-text-fill-color", "transparent", "important");
  state.element.style.setProperty("caret-color", caretColor, "important");
  state.element.style.setProperty("text-shadow", "none", "important");
  state.element.classList.add("shieldtext-editable-source-concealed");
  state.nativeMaskApplied = false;
}

function applyNativeFullEditableMask(state) {
  if (!state?.element) return false;
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
  state.element.classList.remove("shieldtext-editable-source-concealed");
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

function syncEditableOverlayLayout(state) {
  if (!state?.overlayRoot || !state.overlayContent || !state.element?.isConnected) return;

  const element = state.element;
  const overlayHost = ensureEditableOverlayHost(state);
  const rect = element.getBoundingClientRect();
  if (!isElementVisible(element) || !isElementNearViewport(rect)) {
    state.overlayRoot.style.display = "none";
    return;
  }
  if (state.overlayMode === "full-overlay") {
    concealEditableSourceText(state);
  }

  const style = window.getComputedStyle(element);
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

  overlayContent.style.display = "block";
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
  overlayContent.style.color = style.color;
  overlayContent.style.webkitTextFillColor = style.color;
  overlayContent.style.whiteSpace = isSingleLineEditable
    ? "pre"
    : element instanceof HTMLTextAreaElement
      ? "pre-wrap"
      : "pre";

  if (isSingleLineEditable) {
    overlayContent.style.height = `${Math.round(rect.height)}px`;
  }

  overlayContent.style.width = `${Math.round(overlayWidth)}px`;
  overlayContent.style.minWidth = `${Math.round(overlayWidth)}px`;
  overlayContent.style.minHeight = `${Math.round(overlayHeight)}px`;
  overlayContent.style.transform = `translate3d(${-Math.round(Number(element.scrollLeft || 0))}px, ${-Math.round(Number(element.scrollTop || 0))}px, 0)`;
}

function renderEditableOverlay(state, text, spans, settings, tooltip) {
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
  syncEditableOverlayLayout(state);

  if (!state.overlayContent) return;

  concealEditableSourceText(state);

  const renderKey = JSON.stringify({
    text,
    spans,
    interventionMode: settings?.interventionMode || "mask"
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
    const hiddenText = document.createElement("span");
    hiddenText.className = "shieldtext-hidden-mask-text";
    hiddenText.textContent = text.slice(span.start, span.end);
    mask.appendChild(hiddenText);
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
  const shouldUseNativeFullMask =
    doSpansCoverFullText(spans, candidate.text) &&
    applyNativeFullEditableMask(state);
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
