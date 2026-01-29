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
     * Save the recorded video to the server
     * @param {Blob} blob - Video blob
     * @param {Object} sessionConfig - Session configuration for metadata
     * @returns {Promise<Object>} Server response
     */
    async saveToServer(blob, sessionConfig = {}) {
        const formData = new FormData();
        const filename = `session-${Date.now()}.webm`;

        formData.append('file', blob, filename);
        formData.append('groupPath', '~/Magic8/Recordings');
        formData.append('contentType', 'video/webm');

        if (sessionConfig) {
            formData.append('sessionConfig', JSON.stringify(sessionConfig));
        }

        try {
            const response = await fetch(`${g_application_path}/rest/media/upload`, {
                method: 'POST',
                body: formData,
                credentials: 'include'
            });

            if (!response.ok) {
                throw new Error(`Upload failed: ${response.status}`);
            }

            return await response.json();
        } catch (err) {
            console.error('SessionRecorder: Failed to save to server:', err);
            throw err;
        }
    }

    /**
     * Download the recorded video locally
     * @param {Blob} blob - Video blob
     * @param {string} filename - Filename (optional)
     */
    downloadLocally(blob, filename = null) {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.style.display = 'none';
        a.href = url;
        a.download = filename || `magic8-session-${Date.now()}.webm`;
        document.body.appendChild(a);
        a.click();
        setTimeout(() => {
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }, 100);
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
