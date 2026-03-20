/**
 * Camera component — WebRTC camera, device enumeration, frame capture (ESM port)
 * Captures video frames and sends to /rest/face/analyze for biometric analysis.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { applicationPath } from '../core/config.js';

let isCapturing = false;
let isInitialized = false;
let deviceInit = false;
let videoDevices = [];
let selectedDeviceId = null;
let API_ENDPOINT = applicationPath + '/rest/face/analyze';
let CAPTURE_INTERVAL_SECONDS = 5;
let captureIntervalId = 0;
let capturing = false;

function videoElement() {
    return document.querySelector('#facecam');
}

async function initializeAndFindDevices(fCaptureHandler) {
    if (deviceInit) return;
    deviceInit = true;
    let stream;

    try {
        stream = await navigator.mediaDevices.getUserMedia({ video: true });
    } catch (err) {
        console.error("Initial permission error:", err);
        return;
    }

    stream.getTracks().forEach(track => track.stop());

    try {
        const devices = await navigator.mediaDevices.enumerateDevices();
        videoDevices = devices.filter(device => device.kind === 'videoinput');

        if (videoDevices.length > 0) {
            selectedDeviceId = videoDevices[0].deviceId;
            isInitialized = true;
            if (videoDevices.length > 1) {
                pickCamera(fCaptureHandler);
            } else {
                startCapture(fCaptureHandler);
            }
        } else {
            page.toast("warn", "No webcams found.");
        }
    } catch (err) {
        page.toast("error", "Error finding devices.");
        console.error("Device enumeration error:", err);
    }
}

function pickCamera(fCaptureHandler) {
    if (!page.components.dialog) {
        // No dialog system — just use first device
        startCapture(fCaptureHandler);
        return;
    }
    let options = videoDevices.map(d => d.label || ("Camera " + d.deviceId.slice(0, 8)));
    let cfg = {
        label: "Select Camera",
        entityType: "cameraOptions",
        size: 50,
        data: { selectedLabel: options[0] },
        confirm: function(data) {
            let selectedLabel = data.selectedLabel;
            let device = videoDevices.find(d => (d.label || ("Camera " + d.deviceId.slice(0, 8))) === selectedLabel);
            if (device) {
                selectedDeviceId = device.deviceId;
                startCapture(fCaptureHandler);
            }
            page.components.dialog.endDialog();
        },
        cancel: function() {
            page.components.dialog.endDialog();
        }
    };
    page.components.dialog.setDialog(cfg);
}

async function startCapture(fCaptureHandler) {
    if (!selectedDeviceId) {
        page.toast("warn", "Please select a camera first.");
        return;
    }
    if (isCapturing) return;
    isCapturing = true;

    let vid = videoElement();
    if (!vid) {
        page.toast("error", "Video element not found.");
        isCapturing = false;
        return;
    }
    if (vid.srcObject) {
        vid.srcObject.getTracks().forEach(track => track.stop());
    }

    const constraints = { video: { deviceId: { exact: selectedDeviceId } } };

    try {
        const stream = await navigator.mediaDevices.getUserMedia(constraints);
        vid.srcObject = stream;
        vid.onplaying = () => {
            captureIntervalId = setInterval(
                () => captureAndSend(fCaptureHandler),
                CAPTURE_INTERVAL_SECONDS * 1000
            );
        };
    } catch (err) {
        isCapturing = false;
        console.error("getUserMedia error:", err);
        page.toast("error", "Camera access error");
    }
}

async function captureAndSend(fCaptureHandler) {
    let vid = videoElement();
    if (!vid || vid.paused || vid.ended) return;
    if (capturing) return;
    capturing = true;

    try {
        if (!vid.videoWidth || !vid.videoHeight) return;
        const canvas = document.createElement('canvas');
        canvas.width = vid.videoWidth;
        canvas.height = vid.videoHeight;
        const context = canvas.getContext('2d');
        context.drawImage(vid, 0, 0, canvas.width, canvas.height);

        const imageData = canvas.toDataURL('image/jpeg', 0.9);

        let resp = await m.request({
            method: 'POST',
            url: API_ENDPOINT,
            withCredentials: true,
            headers: { 'Content-Type': 'application/json' },
            body: { image_data: imageData }
        });
        if (fCaptureHandler && typeof fCaptureHandler === 'function') {
            fCaptureHandler(resp);
        }
    } catch (err) {
        console.warn("Face analysis request failed:", err?.message || err);
    } finally {
        capturing = false;
    }
}

const camera = {
    devices: () => videoDevices,
    deviceId: () => selectedDeviceId,
    setCaptureInterval: function(seconds) {
        if (seconds > 0) CAPTURE_INTERVAL_SECONDS = seconds;
    },
    startCapture,
    stopCapture: function() {
        if (captureIntervalId) {
            clearInterval(captureIntervalId);
            captureIntervalId = 0;
        }
        let vid = videoElement();
        if (vid && vid.srcObject) {
            vid.srcObject.getTracks().forEach(track => track.stop());
        }
        isCapturing = false;
        capturing = false;
    },
    videoView: () => m('video#facecam', {
        autoplay: true, muted: true, playsinline: true,
        class: "absolute top-0 left-0 opacity-0",
        style: "width: 1px; height: 1px;"
    }),
    cameraListView: () => m('select', {
        class: "w-full px-2 py-1 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-sm",
        onchange: e => { selectedDeviceId = e.target.value; },
        disabled: isCapturing,
        value: selectedDeviceId
    }, videoDevices.map(device =>
        m('option', { value: device.deviceId }, device.label || ("Camera " + device.deviceId.slice(0, 8)))
    )),
    initializeAndFindDevices,
    captureFrame: function() {
        let vid = videoElement();
        if (!vid || vid.paused || vid.ended) return null;
        const canvas = document.createElement('canvas');
        canvas.width = vid.videoWidth;
        canvas.height = vid.videoHeight;
        const context = canvas.getContext('2d');
        context.drawImage(vid, 0, 0, canvas.width, canvas.height);
        return canvas.toDataURL('image/jpeg', 0.9);
    }
};

export { camera };
export default camera;
