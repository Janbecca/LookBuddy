export default {
  tools: {
    fullscreen: {
      enter: 'Fullscreen',
      exit: 'Exit Fullscreen',
      tooltip: 'Fullscreen',
    },
    triggerAlarm: {
      enable: 'Trigger Alarm',
      disable: 'Disable Alarm',
      error: 'Failed to trigger alarm:',
      tooltip: 'Alarm',
      modal: {
        title: 'Manual Alarm',
        description:
          'Please select an alarm type and trigger the alarm. The alarm turns off automatically after 30 seconds.',
        placeholder: 'Please select alarm type',
        confirm: 'Confirm',
        cancel: 'Cancel',
        alarmType: {
          light: 'Light Alarm',
          lightSound: 'Light & Sound Alarm',
        },
      },
    },
    volume: {
      mute: 'Mute',
      unmute: 'Unmute',
      tooltip: 'Volume',
    },
    lighting: {
      enable: 'Turn On Light',
      disable: 'Turn Off Light',
      error: 'Failed to toggle lighting:',
      tooltip: 'Light',
    },
    resolution: {
      title: 'Resolution',
      tooltip: 'Resolution',
      modal: {
        title: 'Resolution',
        placeholder: 'Resolution selection feature coming soon',
      },
      options: {
        auto: 'Auto',
        low: 'Low',
        medium: 'Medium',
        high: 'High',
        max: 'MAX',
      },
    },
    screenshot: {
      title: 'Screenshot',
      error: 'Screenshot failed:',
      tooltip: 'Screenshot',
    },
    preset: {
      title: 'Preset',
      tooltip: 'Preset',
      modal: {
        title: 'Preset Management',
        placeholder: 'Preset management feature coming soon',
      },
    },
    videoSetting: {
      title: 'Video Settings',
      tooltip: 'Settings',
      modal: {
        placeholder: 'Video settings feature coming soon',
      },
      hdr: {
        title: 'HDR',
        off: 'Off',
        on: 'On',
        disabledTip: 'HDR is unavailable when night vision is off',
      },
      nightVision: {
        title: 'Night Vision',
        off: 'Off',
        infraredAuto: 'Infrared Auto',
        spotlightAuto: 'Spotlight Auto',
        infraredAlwaysOn: 'Infrared Always',
        spotlightAlwaysOn: 'Spotlight Always',
      },
      imageSetting: {
        title: 'Image Settings',
        reset: 'Reset',
        brightness: 'Brightness',
        chrome: 'Chrome',
        saturation: 'Saturation',
        contrast: 'Contrast',
        sharpness: 'Sharpness',
      },
      frequency: {
        title: 'Frequency',
        auto: 'Auto',
        hz50: '50Hz',
        hz60: '60Hz',
      },
      rotate: {
        title: 'Direction',
        mirror: 'Mirror View',
        flip: 'Image Rotate',
      },
    },
    layoutMode: {
      title: 'Switch Mode',
      tooltip: 'Mode',
    },
    talkback: {
      title: 'Voice Talkback',
      start: 'Start Talkback',
      stop: 'Stop Talkback',
      ready: 'Talkback Ready',
      initialzing: 'Initializing Talkback...',
      starting: 'Starting Talkback...',
      active: 'Talkback Active',
      stopping: 'Stopping Talkback...',
      error: 'Talkback Error',
      destroyed: 'Talkback Destroyed',
      unknown: 'Unknown Status',
      retry: 'Retry',
      preloadError: 'Talkback features loading failed',
      runtimeError: 'Talkback runtime error',
      description: 'Click button to start or stop voice talkback',
      talking: 'Talking',
      dependenciesNotAvailable:
        'Talkback dependencies not available, please install related packages',
    },
  },
  camera: {
    empty: {
      placeholder: 'No Video',
    },
  },
  common: {
    close: 'Close',
    confirm: 'Confirm',
    cancel: 'Cancel',
    loading: 'Loading...',
    pleaseWait: 'Please wait',
  },
  lazyLoadImage: {
    loading: 'Loading...',
    error: 'Failed to load image',
  },
  playback: {
    loading: 'Loading...',
    empty: 'No playback data',
    thumbnail: {
      alt: 'Playback thumbnail',
      empty: 'No thumbnail',
    },
    tools: {
      rewind10: 'Rewind 10s',
      fastForward10: 'Fast Forward 10s',
      play: 'Play',
      pause: 'Pause',
      mute: 'Mute',
      unmute: 'Unmute',
      fullscreen: 'Fullscreen',
      exitFullscreen: 'Exit Fullscreen',
    },
  },
};
