function normalizeRealtimeWorkerError(error, meta = {}) {
  const name = String(error?.name || meta.name || "Error");
  const message = String(error?.message || meta.message || error || "UNKNOWN_ERROR");
  const phase = String(meta.phase || "unknown");
  const strategy = String(meta.strategy || realtimeWorkerStrategy || "backend-first");
  const errorCode = String(
    meta.errorCode ||
      error?.errorCode ||
      "FOREGROUND_RUNTIME_ERROR"
  );

  return {
    name,
    message,
    phase,
    strategy,
    errorCode,
    retryable: errorCode !== "EXTENSION_CONTEXT_INVALIDATED"
  };
}

function formatDiagnosticError(details) {
  const errorCode = String(details?.errorCode || details?.name || "ERROR");
  const message = String(details?.message || details?.reason || "unknown");
  const phase = String(details?.phase || "").trim();
  return phase ? `${errorCode}: ${message} [${phase}]` : `${errorCode}: ${message}`;
}

function setRealtimeWorkerStatus(status, details = {}) {
  realtimeWorkerStatus = String(status || "idle");
  if (Number.isFinite(details.initLatencyMs)) {
    realtimeWorkerInitLatencyMs = Math.max(0, Math.round(details.initLatencyMs));
  }
  if (details.strategy) {
    realtimeWorkerStrategy = String(details.strategy);
  }
  if (details.failure === null) {
    realtimeWorkerFailure = null;
  } else if (details.failure) {
    realtimeWorkerFailure = details.failure;
  }
}

function markRealtimeWorkerFailure(error, meta = {}) {
  const failure = normalizeRealtimeWorkerError(error, meta);
  realtimeWorkerBackoffUntil = Date.now() + HOT_PATH_WORKER_BACKOFF_MS;
  setRealtimeWorkerStatus("degraded", {
    failure,
    initLatencyMs: meta.initLatencyMs,
    strategy: failure.strategy
  });
  return failure;
}

function resetRealtimeWorkerDiagnostics() {
  realtimeWorkerBackoffUntil = 0;
  setRealtimeWorkerStatus("idle", {
    failure: null,
    initLatencyMs: 0,
    strategy: null
  });
}

function getRealtimeWorkerDiagnostics() {
  return {
    hotPathStatus: realtimeWorkerStatus,
    hotPathErrorCode: realtimeWorkerFailure?.errorCode || "",
    hotPathErrorPhase: realtimeWorkerFailure?.phase || "",
    hotPathErrorMessage: realtimeWorkerFailure?.message || "",
    hotPathWorkerStrategy: realtimeWorkerStrategy || "",
    hotPathDisabledUntil: realtimeWorkerBackoffUntil || 0,
    workerInitLatencyMs: Number(realtimeWorkerInitLatencyMs || 0),
    staleResponseDropCount,
    foregroundApplyCount,
    reconcileOverwriteCount,
    reconcileUnmaskCount,
    inputMaskResetCount,
    skippedHighSignalRetryCount,
    overlayLayoutReuseCount,
    overlayLayoutRebuildCount
  };
}

function cleanupRealtimeWorker() {
  realtimeWorkerBackoffUntil = 0;
}

function scheduleWarmRealtimeWorker() {
  // foreground path is backend-first; startup warmup requests were causing noise
}
