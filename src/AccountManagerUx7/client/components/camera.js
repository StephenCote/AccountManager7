(function () {
    let isCapturing =  false;
    let isInitialized = false;
    let videoEl = null;
    let deviceInit = false;
    let videoDevices = [];
    let selectedDeviceId = null;
    let API_ENDPOINT = g_application_path + '/rest/face/analyze';
    let CAPTURE_INTERVAL_SECONDS = 5;
    let captureIntervalId = 0;
    am7model.models.push(
    {
        name: "cameraOptions", icon: "camera", fields: [
            {
                name: "device",
                label: "Device",
                type: 'list',
                limit: []
            }
        ]
    });

    am7model.forms.cameraOptions = {
        label: "Camera Options",
        fields: {
            device: {
                layout: 'full'
            }
        }
    };

    function videoElement(){
        return document.querySelector('#facecam');
    }

     async function initializeAndFindDevices(fCaptureHandler) {
        if(deviceInit){
            return;
        }
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
                am7model.getModelField("cameraOptions", "device").limit = videoDevices.map(device => (device.label));
                if(videoDevices.length > 1){
                    console.log("Multiple cameras found, prompting user to select.");
                    pickCamera(fCaptureHandler);
                }
                else{
                    console.log("Single camera found, starting capture.");
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

    function pickCamera(fCaptureHandler){
        let entity = am7model.newPrimitive("cameraOptions");
        let inst = am7model.prepareInstance(entity, am7model.forms.cameraOptions);
        let cfg = {
            label: "Camera Options",
            entityType: "cameraOptions",
            size: 50,
            data: {entity, inst},
            confirm: async function (data) {
                let selectedLabel = data.entity.device;
                console.log("Camera selected:", selectedLabel);
                if (!selectedLabel || typeof selectedLabel !== 'string') {
                    page.toast("error", "Please select a camera device.");
                    page.components.dialog.endDialog();
                    return;
                }
                let device = videoDevices.find(d => d.label.toLowerCase() === selectedLabel.toLowerCase());
                if(device){
                    selectedDeviceId = device.deviceId;
                    startCapture(fCaptureHandler);
                }
                else{
                    page.toast("error", "Selected camera not found: " + selectedLabel);
                }
                page.components.dialog.endDialog();
            },
            cancel: async function (data) {
                page.components.dialog.endDialog();
            }
        };
        page.components.dialog.setDialog(cfg);
    }

    async function startCapture(fCaptureHandler) {
        console.log("Starting capture with selected camera.");
        if (!selectedDeviceId) {
            page.toast("warn", "Please select a camera first.");
            return;
        }
        if(isCapturing) {
            page.toast("warn", "Capture is already in progress.");
            return;
        }

        isCapturing = true;
        let videoEl = videoElement();
        if(!videoEl){
            page.toast("error", "Video element not found.");
            return;
        }
        if (videoEl.srcObject) {
            videoEl.srcObject.getTracks().forEach(track => track.stop());
        }

        const constraints = { video: { deviceId: { exact: selectedDeviceId } } };
                
        try {
            const stream = await navigator.mediaDevices.getUserMedia(constraints);
            videoEl.srcObject = stream;
            videoEl.onplaying = () => {
                captureIntervalId = setInterval(
                    () => captureAndSend(fCaptureHandler), 
                    CAPTURE_INTERVAL_SECONDS * 1000
                );
            };
        } catch (err) {
            isCapturing = false;
            console.error("getUserMedia error:", err);
            page.toast("error", "getUserMedia error:", err);
        }
    }
    let capturing = false;
    async function captureAndSend(fCaptureHandler) {
        let videoEl = videoElement();
        console.log("Capture and send triggered.");
        if (!videoEl || videoEl.paused || videoEl.ended) {
            return;
        }
        if(capturing){
            console.log("Capture already in progress, skipping this interval.");
            return;
        }
        capturing = true;
                
        const canvas = document.createElement('canvas');
        canvas.width = videoEl.videoWidth;
        canvas.height = videoEl.videoHeight;
        const context = canvas.getContext('2d');
        context.drawImage(videoEl, 0, 0, canvas.width, canvas.height);

        const imageData = canvas.toDataURL('image/jpeg', 0.9); // 0.9 is quality

        let resp = await m.request({
            method: 'POST',
            url: API_ENDPOINT,
            withCredentials: true,
            headers: { 'Content-Type': 'application/json' },
            body: { image_data: imageData }
        });
        if(fCaptureHandler && typeof fCaptureHandler === 'function') {
            fCaptureHandler(resp);
        }
        // console.log("Capture and send complete:", resp);
        capturing = false;
        return resp;
    }
    let camera = {
        devices: ()=> videoDevices,
        deviceId: ()=> selectedDeviceId,
        startCapture,
        stopCapture: function(){
            //console.log("Stopping camera capture.");
            let videoEl = videoElement();
            if (videoEl && videoEl.srcObject) {
             videoEl.srcObject.getTracks().forEach(track => track.stop());
            }
            isCapturing = false;

        },
        videoView: ()=>  m('video#facecam', { autoplay: true, muted: true, playsinline: true, class: "absolute top-0 left-0 opacity-0", style: "width: 1px; height: 1px;" }),
        cameraListView: () => m('select', {class: "select-field-full", onchange: e => { selectedDeviceId = e.target.value; }, disabled: isCapturing, value: selectedDeviceId},
            videoDevices.map(device => 
                m('option', { value: device.deviceId }, device.label || `Camera (${device.deviceId.slice(0, 8)})`)
            )
        ),
        cameraPickButton: () => m('button', {onclick: startCapture, disabled: isCapturing},'Start'),
        initializeAndFindDevices,
    };

    page.components.camera = camera;

}());
