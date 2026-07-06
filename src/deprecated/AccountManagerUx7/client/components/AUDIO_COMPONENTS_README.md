# Audio Components Refactor

This document describes the refactored audio source and visualization system for the AccountManager application.

## Overview

The audio system has been refactored to use reusable Mithril components that can be used across different views (object view, gallery view, chat views). The key improvement is treating audio sources and visualizers as proper Mithril components with managed lifecycle and state.

## Key Features

1. **Component-based architecture** - Audio sources and visualizers are now Mithril components
2. **Reusable across views** - Can be used in object view, gallery view, and chat views
3. **Proper state management** - Audio playback state is properly managed and cleaned up
4. **No rewind limitation handled** - Audio sources use Web Audio API's `AudioBufferSourceNode` which can only be started once. The system automatically recreates source nodes for replay.
5. **View switching support** - Properly stops and cleans up audio when switching between views or clips

## Utility Functions

### simpleHash(str)

Generates a stable hash from a string for creating unique identifiers.

**Usage**:
```javascript
let hash = page.components.audioComponents.simpleHash("some content");
// Returns: "1a2b3c4d" (example hash in base36)
```

**Purpose**: Used to create stable audio source names based on content, ensuring the same content always generates the same identifier.

## Components

### 1. AudioSource Controller

**Purpose**: Manages audio synthesis, buffering, and playback using Web Audio API.

**Key Features**:
- Audio synthesis from text using voice profiles
- Manages playback state (play/pause/stop)
- Recreates buffer source nodes for replay (required by Web Audio API)
- Provides analyzer node for visualization
- Auto-stops other audio sources when playing (optional)

**Usage**:
```javascript
let audioSource = page.components.audioComponents.createAudioSource({
    id: "unique-id",
    name: "audio-name",
    profileId: "voice-profile-id",
    content: "Text to synthesize",
    autoStopOthers: true,
    onPlayStateChange: (isPlaying) => {
        console.log("Playing:", isPlaying);
    }
});

// Control playback
audioSource.play();
audioSource.pause();
audioSource.stop();
audioSource.togglePlayPause();

// Get state
audioSource.isPlaying();
audioSource.isLoading();
audioSource.hasError();

// Cleanup
audioSource.destroy();
```

### 2. AudioVisualizer Component

**Purpose**: Mithril component that renders audio visualization using AudioMotionAnalyzer.

**Key Features**:
- Visualizes audio from an AudioSource controller
- Click to play/pause
- Configurable appearance
- Auto-cleanup on component removal

**Usage**:
```javascript
m(page.components.audioComponents.AudioVisualizer, {
    audioSource: audioSourceController,
    class: "my-visualizer",
    height: 60,
    gradient: 'prism',
    bgAlpha: 0,
    showBgColor: true,
    visualizerProps: {
        // Additional AudioMotionAnalyzer properties
    }
})
```

### 3. SimpleAudioPlayer Component

**Purpose**: Complete audio player combining AudioSource controller and AudioVisualizer.

**Key Features**:
- All-in-one component for easy integration
- Handles both synthesis and visualization
- Shows loading and error states
- Auto-load option

**Usage**:
```javascript
m(page.components.audioComponents.SimpleAudioPlayer, {
    id: "player-1",
    name: "audio-name",
    profileId: "voice-profile-id",
    content: "Text to synthesize",
    autoPlay: false,           // Auto-play on first render
    autoPlayDelay: 100,        // Delay before auto-play (ms)
    autoStopOthers: true,
    visualizerClass: "w-full",
    height: 60,
    gradient: "prism",
    onPlayStateChange: (isPlaying) => {
        console.log("Playing:", isPlaying);
    },
    onDelete: () => {          // Optional delete callback
        console.log("Delete requested");
    }
})
```

**Features**:
- Play/pause button with material icon
- Optional delete button (shown when `onDelete` callback provided)
- Loading and error states with icons
- Centered controls overlay on visualizer
- Auto-play support (plays once on first render)

### 4. Magic8Ball Component

**Purpose**: Dual audio visualizer in a circular design for chat conversations.

**Key Features**:
- Shows two audio sources (assistant and user) in a split-circle
- Profile images as background
- Auto-plays most recent message
- Handles content changes automatically

**Usage**:
```javascript
m(page.components.audioComponents.Magic8Ball, {
    instanceId: "chat-instance-id",
    messages: chatHistory.messages,
    systemProfileId: systemProfile.objectId,
    userProfileId: userProfile.objectId,
    systemProfileImageUrl: "/path/to/system/image",
    userProfileImageUrl: "/path/to/user/image",
    pruneContent: (content) => {
        // Function to clean up content before synthesis
        return content;
    }
})
```

## Integration with Existing Code

### Object View ([client/components/object.js](../components/object.js))

Audio objects in the object/gallery view continue to use standard HTML5 `<audio>` elements with controls. This provides native browser controls and is appropriate for file-based audio playback.

**Change**: Separated video and audio handling for clarity.

### Chat View ([client/view/chat.js](../view/chat.js))

#### Standard Audio Mode
When audio mode is enabled (but not Magic8), each chat message gets a `SimpleAudioPlayer` component:

```javascript
// Generate stable name based on chat objectId, role, and content hash
let contentForHash = pruneAll(msg.content);
let contentHash = page.components.audioComponents.simpleHash(contentForHash);
let name = inst.api.objectId() + "-" + msg.role + "-" + contentHash;

// Auto-play only the last message, and only once per session
let shouldAutoPlay = lastMsg && !hasAutoPlayedThisSession;
if (shouldAutoPlay) {
    hasAutoPlayedThisSession = true;
}

m(page.components.audioComponents.SimpleAudioPlayer, {
    id: name + "-" + aidx,
    name: name,
    profileId: profileId,
    content: contentForHash,
    autoPlay: shouldAutoPlay,      // Auto-play last message only
    autoPlayDelay: 200,
    autoStopOthers: true,
    visualizerClass: "audio-container block w-full",
    height: 60,
    gradient: "prism",
    onDelete: () => {
        console.log("Delete audio:", name);
    }
})
```

**Naming Convention**: Audio sources are named using the pattern: `{chatObjectId}-{role}-{contentHash}`
- This ensures stable identifiers across sessions
- Same content always generates the same name
- Enables caching and prevents duplicate synthesis

**Auto-Play Behavior**:
- Only the last message in the conversation auto-plays
- Auto-play happens only once per session (tracked with `hasAutoPlayedThisSession`)
- 200ms delay allows visualizer to be ready before playback starts

#### Magic8 Mode
When Magic8 mode is enabled, the entire results view is replaced with the `Magic8Ball` component:

```javascript
m(page.components.audioComponents.Magic8Ball, {
    instanceId: inst.api.objectId(),
    messages: chatCfg.history?.messages || [],
    systemProfileId: chatCfg?.system?.profile?.objectId,
    userProfileId: chatCfg?.user?.profile?.objectId,
    systemProfileImageUrl: sysUrl,
    userProfileImageUrl: usrUrl,
    pruneContent: pruneAll
})
```

### Audio Manager ([client/components/audio.js](../components/audio.js))

The existing audio.js module has been extended with:

1. **Registry for new audio source controllers**:
   - `registerAudioSource(id, controller)` - Register a new audio source
   - `unregisterAudioSource(id)` - Unregister an audio source
   - `stopAllAudioSourceControllers(except)` - Stop all registered sources

2. **Backward compatibility**: The old audio system (`audioMap`, `audioSource`, `visualizers`) remains intact for any code still using it.

## Technical Details

### Audio Source Replay

Web Audio API's `AudioBufferSourceNode` can only be started once. To support replay:

1. The buffer is retained after first load
2. A new `AudioBufferSourceNode` is created each time playback is requested
3. The new source is connected to the analyzer and destination
4. The old source node is discarded

This is handled automatically in the `createSourceNode()` function.

### View Switching

When switching between views or audio clips:

1. Each AudioSource controller is registered with the audio manager
2. When a new audio plays with `autoStopOthers: true`:
   - All registered controllers are stopped (new system)
   - All old audio sources are stopped (legacy system)
3. When a component is removed (`onremove`):
   - The controller is destroyed
   - Audio context is closed
   - Controller is unregistered

### State Management

Each AudioSource controller maintains its own state:
```javascript
{
    id: "unique-id",
    name: "audio-name",
    profileId: "voice-profile-id",
    content: "text content",
    context: AudioContext,
    buffer: AudioBuffer,
    sourceNode: AudioBufferSourceNode,
    analyzerNode: AnalyserNode,
    isPlaying: false,
    isLoading: false,
    error: null
}
```

## Migration Guide

### For New Code

Use the new components:
```javascript
// Simple audio player
m(page.components.audioComponents.SimpleAudioPlayer, {
    name: "my-audio",
    profileId: profileId,
    content: "Text to speak",
    autoLoad: true
})

// Or create controller separately for more control
let audioSource = page.components.audioComponents.createAudioSource({
    name: "my-audio",
    profileId: profileId,
    content: "Text to speak"
});

m(page.components.audioComponents.AudioVisualizer, {
    audioSource: audioSource
})
```

### For Existing Code

The old system remains functional:
```javascript
// Old way still works
page.components.audio.createAudioVisualizer(name, index, profileId, autoPlay, content);
page.components.audio.configureAudio(enabled);
```

But consider migrating to the new system for better lifecycle management.

## Testing

To test the new system:

1. **Object View**:
   - Navigate to an audio file in object/gallery view
   - Audio should play with standard HTML5 controls
   - Switching between audio files should work correctly

2. **Chat Standard Audio Mode**:
   - Enable audio mode in chat (not Magic8)
   - Each message should show a visualizer
   - Clicking a visualizer should play that audio
   - Only one audio should play at a time
   - Switching between chat messages should work

3. **Chat Magic8 Mode**:
   - Enable Magic8 mode in chat
   - Should see circular dual visualizer
   - Top half = assistant (last message)
   - Bottom half = user (second to last message)
   - Clicking either half should play that audio
   - Content changes should update the visualizers

4. **View Switching**:
   - Start playing audio in one view
   - Switch to another view
   - Previous audio should stop
   - New audio should be playable

## File Structure

```
client/
├── components/
│   ├── audioComponents.js       # New audio component system
│   ├── audio.js                 # Legacy audio system (extended)
│   ├── object.js                # Object view (updated)
│   └── AUDIO_COMPONENTS_README.md  # This file
└── view/
    └── chat.js                  # Chat view (updated)
```

## Known Limitations

1. **No seek support**: Audio sources cannot seek/rewind mid-playback (Web Audio API limitation)
2. **One-shot playback**: Each play requires creating a new source node
3. **Memory usage**: Audio buffers are kept in memory for replay
4. **Browser support**: Requires modern browsers with Web Audio API support

## Future Enhancements

Possible future improvements:

1. Add seek/scrub support using different audio backend
2. Add playback controls (play, pause, stop buttons)
3. Add progress indicators
4. Add volume controls
5. Support for streaming audio (long content)
6. Preload audio buffers for faster playback
7. Add audio effects (reverb, echo, etc.)
8. Support for multiple simultaneous audio sources (mixing)

## Support

For issues or questions about the audio component system:

1. Check this documentation
2. Review the source code in `audioComponents.js`
3. Look at usage examples in `chat.js` and `object.js`
4. Check the browser console for errors
