/**
 * SessionRecorder - Canvas and audio recording for Magic8 sessions
 * Records the visual canvas and audio streams into a video file
 */
class SessionRecorder {
    constructor() {
        this.mediaRecorder = null;
        this.recordedChunks = [];
        this.isRecording = false;
        this.canvas = null;
        this.audioDestination = null;
        this.combinedStream = null;
        this.startTime = null;
        this.duration = 0;
        this.maxDuration = 30 * 60 * 1000; // 30 minutes default max

        this.onRecordingStart = null;
        this.onRecordingStop = null;
        this.onDataAvailable = null;
        this.onError = null;
    }

    /**
     * Start recording canvas and audio
     * @param {HTMLCanvasElement} canvasElement - Canvas to capture
     * @param {MediaStreamAudioDestinationNode} audioDestination - Audio destination from AudioEngine
     * @param {Object} options - Recording options
     * @returns {Promise<void>}
     */
    async startRecording(canvasElement, audioDestination = null, options = {}) {
        if (this.isRecording) {
            console.warn('SessionRecorder: Already recording');
            return;
        }

        this.canvas = canvasElement;
        this.audioDestination = audioDestination;
        this.recordedChunks = [];
        this.maxDuration = (options.maxDurationMin || 30) * 60 * 1000;

        try {
            // Capture canvas stream
            const fps = options.fps || 30;
            const canvasStream = canvasElement.captureStream(fps);

            // Combine with audio if available
            if (audioDestination && audioDestination.stream) {
                const audioStream = audioDestination.stream;
                this.combinedStream = new MediaStream([
                    ...canvasStream.getVideoTracks(),
                    ...audioStream.getAudioTracks()
                ]);
            } else {
                this.combinedStream = canvasStream;
            }

            // Determine supported MIME type
            const mimeType = this._getSupportedMimeType();

            // Create MediaRecorder
            const recorderOptions = {
                mimeType: mimeType,
                videoBitsPerSecond: options.videoBitrate || 5000000 // 5 Mbps
            };

            this.mediaRecorder = new MediaRecorder(this.combinedStream, recorderOptions);

            // Handle data available
            this.mediaRecorder.ondataavailable = (event) => {
                if (event.data.size > 0) {
                    this.recordedChunks.push(event.data);
                    if (this.onDataAvailable) {
                        this.onDataAvailable(event.data);
                    }
                }
            };

            // Handle errors
            this.mediaRecorder.onerror = (event) => {
                console.error('SessionRecorder: Error:', event.error);
                if (this.onError) {
                    this.onError(event.error);
                }
            };

            // Start recording with 1-second chunks
            this.mediaRecorder.start(1000);
            this.isRecording = true;
            this.startTime = Date.now();

            if (this.onRecordingStart) {
                this.onRecordingStart();
            }

            // Set up max duration timer
            if (this.maxDuration > 0) {
                setTimeout(() => {
                    if (this.isRecording) {
                        console.log('SessionRecorder: Max duration reached');
                        this.stopRecording();
                    }
                }, this.maxDuration);
            }

        } catch (err) {
            console.error('SessionRecorder: Failed to start recording:', err);
            if (this.onError) {
                this.onError(err);
            }
            throw err;
        }
    }

    /**
     * Stop recording and return the video blob
     * @returns {Promise<Blob>} Video blob
     */
    stopRecording() {
        return new Promise((resolve, reject) => {
            if (!this.isRecording || !this.mediaRecorder) {
                reject(new Error('Not recording'));
                return;
            }

            this.mediaRecorder.onstop = () => {
                this.duration = Date.now() - this.startTime;
                this.isRecording = false;

                const blob = new Blob(this.recordedChunks, {
                    type: this.mediaRecorder.mimeType || 'video/webm'
                });

                if (this.onRecordingStop) {
                    this.onRecordingStop(blob, this.duration);
                }

                // Clean up streams
                this._cleanupStreams();

                resolve(blob);
            };

            this.mediaRecorder.stop();
        });
    }

    /**
     * Save the recorded video to the server as a data.data object
     * @param {Blob} blob - Video blob
     * @param {string} [sessionName] - Session name for the recording filename
     * @returns {Promise<Object>} Created server object
     */
    async saveToServer(blob, sessionName) {
        try {
            // Ensure ~/Magic8/Recordings directory exists
            let dir = await page.findObject("auth.group", "DATA", "~/Magic8/Recordings");
            if (!dir || !dir.objectId) {
                dir = await page.makePath("auth.group", "data", "~/Magic8/Recordings");
            }

            // Convert blob to base64
            const base64Data = await this._blobToBase64(blob);

            // Build filename from session name + timestamp
            const ts = new Date().toISOString().replace(/[:.]/g, '-').substring(0, 19);
            const name = sessionName
                ? sessionName + ' ' + ts + '.webm'
                : 'session-' + ts + '.webm';

            // Create data.data object
            let obj = am7model.newPrimitive("data.data");
            obj.name = name;
            obj.contentType = blob.type || 'video/webm';
            obj.compressionType = 'none';
            obj.groupId = dir.id;
            obj.groupPath = dir.path;
            obj.dataBytesStore = base64Data;

            const saved = await page.createObject(obj);
            console.log('SessionRecorder: Saved to server as', name);
            return saved;
        } catch (err) {
            console.error('SessionRecorder: Failed to save to server:', err);
            throw err;
        }
    }

    /**
     * Convert a Blob to a base64 string
     * @param {Blob} blob
     * @returns {Promise<string>}
     * @private
     */
    _blobToBase64(blob) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onloadend = () => {
                // Strip the data URL prefix (e.g. "data:video/webm;base64,")
                const base64 = reader.result.split(',')[1];
                resolve(base64);
            };
            reader.onerror = reject;
            reader.readAsDataURL(blob);
        });
    }

    /**
     * Get supported MIME type for recording
     * @returns {string}
     * @private
     */
    _getSupportedMimeType() {
        const types = [
            'video/webm;codecs=vp9,opus',
            'video/webm;codecs=vp9',
            'video/webm;codecs=vp8,opus',
            'video/webm;codecs=vp8',
            'video/webm',
            'video/mp4'
        ];

        for (const type of types) {
            if (MediaRecorder.isTypeSupported(type)) {
                return type;
            }
        }

        return 'video/webm';
    }

    /**
     * Clean up stream tracks
     * @private
     */
    _cleanupStreams() {
        if (this.combinedStream) {
            this.combinedStream.getTracks().forEach(track => {
                track.stop();
            });
            this.combinedStream = null;
        }
    }

    /**
     * Pause recording
     */
    pause() {
        if (this.isRecording && this.mediaRecorder && this.mediaRecorder.state === 'recording') {
            this.mediaRecorder.pause();
        }
    }

    /**
     * Resume recording
     */
    resume() {
        if (this.mediaRecorder && this.mediaRecorder.state === 'paused') {
            this.mediaRecorder.resume();
        }
    }

    /**
     * Get recording state
     * @returns {string} 'inactive' | 'recording' | 'paused'
     */
    getState() {
        return this.mediaRecorder ? this.mediaRecorder.state : 'inactive';
    }

    /**
     * Get current recording duration in milliseconds
     * @returns {number}
     */
    getCurrentDuration() {
        if (!this.isRecording || !this.startTime) return 0;
        return Date.now() - this.startTime;
    }

    /**
     * Get formatted duration string (MM:SS)
     * @returns {string}
     */
    getFormattedDuration() {
        const ms = this.isRecording ? this.getCurrentDuration() : this.duration;
        const seconds = Math.floor(ms / 1000);
        const minutes = Math.floor(seconds / 60);
        const remainingSeconds = seconds % 60;
        return `${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}`;
    }

    /**
     * Check if MediaRecorder is supported
     * @returns {boolean}
     */
    static isSupported() {
        return typeof MediaRecorder !== 'undefined' &&
               typeof HTMLCanvasElement.prototype.captureStream === 'function';
    }

    /**
     * Clean up all resources
     */
    dispose() {
        if (this.isRecording) {
            this.mediaRecorder.stop();
        }
        this._cleanupStreams();
        this.recordedChunks = [];
        this.mediaRecorder = null;
        this.onRecordingStart = null;
        this.onRecordingStop = null;
        this.onDataAvailable = null;
        this.onError = null;
    }
}

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = SessionRecorder;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.SessionRecorder = SessionRecorder;
}
