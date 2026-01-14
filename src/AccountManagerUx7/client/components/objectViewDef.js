(function() {
    /// Object View Model Definitions
    /// These define the structure for different object view types

    am7model.models.push({
        name: "view.objectDisplay",
        icon: "visibility",
        label: "Object Display",
        fields: [
            {
                name: "objectId",
                type: "string",
                label: "Object ID"
            },
            {
                name: "active",
                type: "boolean",
                label: "Active",
                default: false
            },
            {
                name: "maxMode",
                type: "boolean",
                label: "Maximize",
                default: false
            },
            {
                name: "viewType",
                type: "string",
                label: "View Type",
                virtual: true,
                ephemeral: true
            }
        ]
    });

    /// Form definitions for object viewers
    /// Note: These forms include a dummy 'field' property to bypass model field lookup
    /// when used in the legacy view/object.js dialog rendering system
    am7model.forms.portraitView = {
        label: "Portrait Viewer",
        icon: "account_circle",
        viewType: "portrait",
        fields: {
            objectId: {
                layout: "full",
                format: "portrait",
                field: {name: "objectId", type: "string"}
            }
        }
    };

    am7model.forms.imageView = {
        label: "Image Viewer",
        icon: "image",
        viewType: "image",
        fields: {
            objectId: {
                layout: "full",
                format: "image",
                field: {name: "objectId", type: "string"}
            }
        }
    };

    am7model.forms.videoView = {
        label: "Video Viewer",
        icon: "videocam",
        viewType: "video",
        fields: {
            objectId: {
                layout: "full",
                format: "video",
                field: {name: "objectId", type: "string"}
            }
        }
    };

    am7model.forms.audioView = {
        label: "Audio Player",
        icon: "audiotrack",
        viewType: "audio",
        fields: {
            objectId: {
                layout: "full",
                format: "audio",
                field: {name: "objectId", type: "string"}
            }
        }
    };

    am7model.forms.textView = {
        label: "Text Viewer",
        icon: "article",
        viewType: "text",
        fields: {
            objectId: {
                layout: "full",
                format: "text",
                field: {name: "objectId", type: "string"}
            }
        }
    };

    am7model.forms.pdfView = {
        label: "PDF Viewer",
        icon: "picture_as_pdf",
        viewType: "pdf",
        fields: {
            objectId: {
                layout: "full",
                format: "pdf",
                field: {name: "objectId", type: "string"}
            }
        }
    };

    am7model.forms.noteView = {
        label: "Note Viewer",
        icon: "note",
        viewType: "note",
        fields: {
            text: {
                layout: "full",
                format: "markdown",
                field: {name: "text", type: "string"}
            }
        }
    };

    am7model.forms.messageView = {
        label: "Message Viewer",
        icon: "message",
        viewType: "message",
        fields: {
            data: {
                layout: "full",
                format: "messageContent",
                field: {name: "data", type: "string"}
            }
        }
    };

}());
