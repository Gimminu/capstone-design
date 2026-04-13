function restoreEditableValueState(state) {
  if (!state?.element) return;
  if (state.originalTitle) {
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
  state.overlayBarsKey = "";
  state.element.style.color = state.originalColor || "";
  state.element.style.webkitTextFillColor = state.originalWebkitTextFillColor || "";
  state.element.style.caretColor = state.originalCaretColor || "";
  state.element.style.textShadow = state.originalTextShadow || "";
  state.element.style.webkitTextSecurity = state.originalWebkitTextSecurity || "";
  state.element.style.textSecurity = state.originalTextSecurity || "";
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

function getEditableTextMeasureContext() {
  if (!editableTextMeasureCanvas) {
    editableTextMeasureCanvas = document.createElement("canvas");
  }

  return editableTextMeasureCanvas.getContext("2d");
}

function parsePixelValue(value) {
  const numberValue = Number.parseFloat(String(value || "0"));
  return Number.isFinite(numberValue) ? numberValue : 0;
}

function getSingleLineEditableFont(style) {
  return style.font && style.font !== "normal normal normal normal 16px / normal sans-serif"
    ? style.font
    : [
        style.fontStyle,
        style.fontVariant,
        style.fontWeight,
        style.fontStretch,
        style.fontSize,
        style.lineHeight && style.lineHeight !== "normal" ? `/${style.lineHeight}` : "",
        style.fontFamily
      ]
        .filter(Boolean)
        .join(" ");
}

function measureEditableTextWidth(text, style) {
  const context = getEditableTextMeasureContext();
  if (!context) {
    return 0;
  }

  context.font = getSingleLineEditableFont(style);
  let width = context.measureText(String(text || "")).width;
  const letterSpacing = parsePixelValue(style.letterSpacing);
  if (letterSpacing !== 0 && text.length > 1) {
    width += letterSpacing * Math.max(0, text.length - 1);
  }
  return width;
}

function renderEditableNativeMask(state, tooltip, settings) {
  if (!state?.element) return;

  if (state.overlayRoot?.isConnected) {
    state.overlayRoot.remove();
  }
  state.overlayRoot = null;
  state.overlayContent = null;
  state.overlayMode = "";

  if (tooltip) {
    state.element.title = tooltip;
  } else {
    state.element.removeAttribute("title");
  }
  state.element.style.webkitTextSecurity =
    settings?.interventionMode === "hide" ? "disc" : "square";
  state.element.style.textSecurity =
    settings?.interventionMode === "hide" ? "disc" : "square";
  state.element.style.setProperty("color", "transparent", "important");
  state.element.style.setProperty("-webkit-text-fill-color", "transparent", "important");
  state.element.style.setProperty("text-shadow", "none", "important");
  state.nativeMaskApplied = true;
  MASKED_EDITABLE_STATE_IDS.add(state.nodeId);
}

function getEditableOverlayHost(element) {
  if (element instanceof HTMLElement) {
    if (element.offsetParent instanceof HTMLElement) {
      return element.offsetParent;
    }

    if (element.parentElement instanceof HTMLElement) {
      return element.parentElement;
    }
  }

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
  const paddingTop = Number.parseFloat(style.paddingTop || "0") || 0;
  const paddingBottom = Number.parseFloat(style.paddingBottom || "0") || 0;
  const computedLineHeight = style.lineHeight === "normal"
    ? `${Math.max(0, rect.height - paddingTop - paddingBottom)}px`
    : style.lineHeight;
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
    overlayContent.style.display = "flex";
    overlayContent.style.alignItems = "center";
    overlayContent.style.height = `${Math.round(rect.height)}px`;
    overlayContent.style.paddingTop = "0";
    overlayContent.style.paddingBottom = "0";
    overlayContent.style.lineHeight =
      style.lineHeight === "normal" ? style.fontSize : style.lineHeight;
  }

  if (state.overlayMode === "single-line-bars") {
    overlayContent.style.width = `${Math.round(rect.width)}px`;
    overlayContent.style.minWidth = `${Math.round(rect.width)}px`;
    overlayContent.style.minHeight = `${Math.round(rect.height)}px`;
    overlayContent.style.transform = "none";
    overlayContent.style.padding = "0";
    overlayContent.style.border = "0";
    syncEditableSingleLineBarLayout(state, style, rect);
    return;
  }

  overlayContent.style.width = `${Math.round(overlayWidth)}px`;
  overlayContent.style.minWidth = `${Math.round(overlayWidth)}px`;
  overlayContent.style.minHeight = `${Math.round(overlayHeight)}px`;
  overlayContent.style.transform = `translate3d(${-Math.round(Number(element.scrollLeft || 0))}px, ${-Math.round(Number(element.scrollTop || 0))}px, 0)`;
}

function syncEditableSingleLineBarLayout(state, style, rect) {
  if (!state?.overlayContent || !state?.element) {
    return;
  }

  const text = String(state.maskedText || getEditableElementText(state.element) || "");
  const spans = normalizeEvidenceSpans(state.maskedSpans, text);
  const paddingLeft = parsePixelValue(style.paddingLeft);
  const paddingRight = parsePixelValue(style.paddingRight);
  const paddingTop = parsePixelValue(style.paddingTop);
  const paddingBottom = parsePixelValue(style.paddingBottom);
  const scrollLeft = Number(state.element.scrollLeft || 0);
  const lineHeight =
    style.lineHeight === "normal"
      ? Math.max(0, parsePixelValue(style.fontSize) * 1.2)
      : parsePixelValue(style.lineHeight);
  const contentHeight = Math.max(0, rect.height - paddingTop - paddingBottom);
  const topOffset = paddingTop + Math.max(0, (contentHeight - lineHeight) / 2);
  const maxWidth = Math.max(0, rect.width - paddingLeft - paddingRight);
  const barsKey = JSON.stringify({
    text,
    spans: spans.map((span) => [span.start, span.end]),
    paddingLeft: Math.round(paddingLeft),
    paddingRight: Math.round(paddingRight),
    paddingTop: Math.round(paddingTop),
    paddingBottom: Math.round(paddingBottom),
    scrollLeft: Math.round(scrollLeft),
    lineHeight: Math.round(lineHeight),
    rectWidth: Math.round(rect.width),
    rectHeight: Math.round(rect.height)
  });
  const fragment = document.createDocumentFragment();

  if (state.overlayBarsKey === barsKey) {
    overlayLayoutReuseCount += 1;
    return;
  }

  overlayLayoutRebuildCount += 1;
  state.overlayBarsKey = barsKey;
  state.overlayContent.textContent = "";

  for (const span of spans) {
    const prefixWidth = measureEditableTextWidth(text.slice(0, span.start), style);
    const spanWidth = Math.max(
      10,
      measureEditableTextWidth(text.slice(span.start, span.end), style)
    );
    const left = paddingLeft + prefixWidth - scrollLeft;
    const right = left + spanWidth;

    if (right <= paddingLeft || left >= paddingLeft + maxWidth) {
      continue;
    }

    const bar = document.createElement("span");
    bar.className =
      state.element instanceof HTMLTextAreaElement
        ? "shieldtext-editable-bar-mask"
        : "shieldtext-editable-bar-mask shieldtext-editable-bar-mask-single";
    bar.style.left = `${Math.round(Math.max(paddingLeft, left))}px`;
    bar.style.top = `${Math.round(Math.max(0, topOffset))}px`;
    bar.style.width = `${Math.round(Math.min(paddingLeft + maxWidth, right) - Math.max(paddingLeft, left))}px`;
    bar.style.height = `${Math.round(Math.max(lineHeight, Math.min(contentHeight || lineHeight, lineHeight + 2)))}px`;
    fragment.appendChild(bar);
  }

  state.overlayContent.appendChild(fragment);
}

function renderEditableSingleLineBarMask(state, text, spans, settings, tooltip) {
  ensureEditableOverlay(state);
  state.overlayMode = "single-line-bars";
  state.maskedText = text;
  state.maskedSpans = spans;
  state.overlayTooltip = tooltip;

  state.element.style.webkitTextSecurity = state.originalWebkitTextSecurity || "";
  state.element.style.textSecurity = state.originalTextSecurity || "";
  state.element.style.color = state.originalColor || "";
  state.element.style.webkitTextFillColor = state.originalWebkitTextFillColor || "";
  state.element.style.textShadow = state.originalTextShadow || "";
  state.nativeMaskApplied = false;

  syncEditableOverlayLayout(state);
  state.overlayRoot.removeAttribute("title");
  state.element.removeAttribute("title");
  MASKED_EDITABLE_STATE_IDS.add(state.nodeId);
  scheduleEditableOverlaySync(1);
}

function renderEditableOverlay(state, text, spans, settings, tooltip) {
  ensureEditableOverlay(state);
  state.overlayMode = "full-overlay";
  state.maskedText = text;
  state.maskedSpans = spans;
  state.overlayTooltip = tooltip;
  syncEditableOverlayLayout(state);

  if (!state.overlayContent) return;

  state.element.style.webkitTextSecurity = state.originalWebkitTextSecurity || "";
  state.element.style.textSecurity = state.originalTextSecurity || "";
  const computedStyle = window.getComputedStyle(state.element);
  const caretColor = computedStyle.caretColor && computedStyle.caretColor !== "auto"
    ? computedStyle.caretColor
    : computedStyle.color;
  state.element.style.setProperty("color", "transparent", "important");
  state.element.style.setProperty("-webkit-text-fill-color", "transparent", "important");
  state.element.style.setProperty("caret-color", caretColor, "important");
  state.element.style.setProperty("text-shadow", "none", "important");
  state.nativeMaskApplied = false;

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
    mask.textContent = text.slice(span.start, span.end);
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
  scheduleEditableOverlaySync(1);
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

function shouldUseEditableNativeMask(element, spans, text) {
  if (!(element instanceof HTMLInputElement)) {
    return false;
  }

  const inputType = String(element.type || "text").toLowerCase();
  if (!["text", "search", ""].includes(inputType)) {
    return false;
  }

  return doSpansCoverFullText(spans, text);
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

  const renderMode =
    shouldUseEditableNativeMask(state.element, spans, candidate.text)
      ? "native-mask"
      : "overlay";

  const decisionKey = JSON.stringify({
    text: candidate.text,
    categories: outcome.categories,
    interventionMode: settings?.interventionMode || "mask",
    tooltip: buildMaskTooltip(outcome.categories, outcome.reasons, settings),
    renderMode,
    spans
  });
  if (decisionKey === state.lastDecisionKey) {
    if (renderMode === "overlay" || renderMode === "single-line-bars") {
      syncEditableOverlayLayout(state);
    }
    return;
  }

  const tooltip = buildMaskTooltip(outcome.categories, outcome.reasons, settings);
  if (renderMode === "native-mask") {
    renderEditableNativeMask(state, tooltip, settings);
  } else {
    renderEditableOverlay(state, candidate.text, spans, settings, tooltip);
  }
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
    Math.min(1, Math.max(0, Number(frames || 0)))
  );

  if (extensionContextInvalidated || overlaySyncFrameId) {
    return;
  }

  overlaySyncFrameId = window.requestAnimationFrame(() => {
    overlaySyncFrameId = null;
    syncAllMaskedEditableOverlays();
    if (pendingEditableOverlaySyncFrames > 0) {
      pendingEditableOverlaySyncFrames = 0;
    }
  });
}
