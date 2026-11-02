// DVR-chan 4.1, adapted for YTChat's built-in WebExtension.
// Original: https://greasyfork.org/scripts/485020
// License: MIT
(function () {
  "use strict";

  if (window.__ytccDvrChanInstalled) return;
  window.__ytccDvrChanInstalled = true;

  const MAX_DVR_SECS = 43200 * 14;

  function modifyPlayerResponse(pr) {
    const { streamingData, videoDetails, playerConfig, microformat } = pr;
    if (!videoDetails || !videoDetails.isLive) return;

    videoDetails.isLiveDvrEnabled = true;

    const mediaCommonConfig = playerConfig && playerConfig.mediaCommonConfig;
    if (mediaCommonConfig) {
      mediaCommonConfig.useServerDrivenAbr = false;
      if (mediaCommonConfig.serverPlaybackStartConfig) {
        mediaCommonConfig.serverPlaybackStartConfig.enable = false;
      }
    }

    if (!streamingData) return;

    if (
      streamingData.serverAbrStreamingUrl &&
      (streamingData.hlsManifestUrl || streamingData.dashManifestUrl)
    ) {
      delete streamingData.serverAbrStreamingUrl;
    }

    if (Array.isArray(streamingData.adaptiveFormats) && streamRunningOver12h(microformat)) {
      for (const format of streamingData.adaptiveFormats) {
        format.maxDvrDurationSec = MAX_DVR_SECS;
      }
    }
  }

  function streamRunningOver12h(microformat) {
    const live =
      microformat &&
      microformat.playerMicroformatRenderer &&
      microformat.playerMicroformatRenderer.liveBroadcastDetails;
    if (!live || !live.startTimestamp) return false;
    const seconds = (Date.now() - new Date(live.startTimestamp).getTime()) / 1000;
    return seconds > 43200;
  }

  function patchResponse(data) {
    if (!data || typeof data !== "object") return false;
    if (data.videoDetails) modifyPlayerResponse(data);
    else if (data.playerResponse && data.playerResponse.videoDetails) modifyPlayerResponse(data.playerResponse);
    else return false;
    return true;
  }

  const previousInitialDescriptor = Object.getOwnPropertyDescriptor(window, "ytInitialPlayerResponse");
  const previousInitialSetter = previousInitialDescriptor && previousInitialDescriptor.set;
  let initialResponse = window.ytInitialPlayerResponse;
  if (!patchResponse(initialResponse)) {
    try {
      Object.defineProperty(window, "ytInitialPlayerResponse", {
        configurable: true,
        get() {
          return initialResponse;
        },
        set(value) {
          if (previousInitialSetter) previousInitialSetter.call(this, value);
          initialResponse = value;
          if (patchResponse(value)) {
            Object.defineProperty(window, "ytInitialPlayerResponse", {
              configurable: true,
              writable: true,
              value,
            });
          }
        },
      });
    } catch (_error) {
      patchResponse(window.ytInitialPlayerResponse);
    }
  }

  const nativeParse = JSON.parse;
  JSON.parse = function (text, reviver) {
    const data = nativeParse.call(this, text, reviver);
    try {
      patchResponse(data);
    } catch (_error) {
      // Never let DVR patching break YouTube's normal JSON parsing.
    }
    return data;
  };

  const DVR_FLAG = "html5_max_live_dvr_window_plus_margin_secs";

  function liftDvrCap(ytcfg) {
    if (ytcfg.__dvrCapLifted) return;
    const store = typeof ytcfg.d === "function" && ytcfg.d();
    const players = store && store.WEB_PLAYER_CONTEXT_CONFIGS;
    if (!players) return;
    for (const id in players) {
      const cfg = players[id];
      if (cfg && typeof cfg.serializedExperimentFlags === "string") {
        cfg.serializedExperimentFlags = cfg.serializedExperimentFlags.replace(
          new RegExp(DVR_FLAG + "=[\\d.]+"),
          DVR_FLAG + "=" + MAX_DVR_SECS
        );
      }
    }
    ytcfg.__dvrCapLifted = true;
  }

  function hookYtcfg(ytcfg) {
    if (!ytcfg || ytcfg.__dvrHooked) return ytcfg;
    ytcfg.__dvrHooked = true;
    const nativeSet = ytcfg.set;
    if (typeof nativeSet === "function") {
      ytcfg.set = function () {
        const result = nativeSet.apply(this, arguments);
        try {
          liftDvrCap(ytcfg);
        } catch (_error) {
          // Try again on the next config write.
        }
        return result;
      };
    }
    try {
      liftDvrCap(ytcfg);
    } catch (_error) {
      // Config may not be ready yet.
    }
    return ytcfg;
  }

  let cfgRef = hookYtcfg(window.ytcfg);
  try {
    Object.defineProperty(window, "ytcfg", {
      configurable: true,
      get() {
        return cfgRef;
      },
      set(value) {
        cfgRef = hookYtcfg(value);
      },
    });
  } catch (_error) {
    // Already present and non-configurable; the direct hook above is enough.
  }
})();
