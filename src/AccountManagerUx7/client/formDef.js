(function () {
    /// TODO: Currently out of date
    let forms = {
        access: {},
        auth: {},
        common: {},
        crypto: {},
        data: {},
        identity: {},
        message: {},
        olio: {},
        policy: {},
        system: {}
    };

    forms.nameIdType = {
        fields: ["name"]
    };
    forms.attribute = {
        label: "Attribute",
        format: "table",
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                altIcon: 'check',
                altCondition: ['edit'],
                function: 'newEntry',
                altFunction: 'checkEntry'
            },
            edit: {
                label: 'Edit',
                icon: 'edit',
                function: 'editEntry',
                condition: ['select']
            },
            cancel: {
                label: 'Cancel',
                icon: 'cancel',
                function: 'cancelEntry',
                condition: ['select', 'edit']
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteEntry',
                condition: ['select']
            }

        },

        fields: {
            valueType: {
                layout: "one"
            },
            name: {
                layout: "third"
            },
            value: {
                layout: "third"
            }
        }
    };

    forms.control = {
        label: "Control",
        requiredAttributes: [],
        format: "table",
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                altIcon: 'check',
                altCondition: ['edit'],
                function: 'newEntry',
                altFunction: 'checkEntry'
            },
            edit: {
                label: 'Edit',
                icon: 'edit',
                function: 'editEntry',
                condition: ['select']
            },
            cancel: {
                label: 'Cancel',
                icon: 'cancel',
                function: 'cancelEntry',
                condition: ['select', 'edit']
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteEntry',
                condition: ['select']
            }

        },

        fields: {
            controlType: {
                layout: "third"
            },
            controlId: {
                layout: "third"
            },
            action: {
                layout: "third"
            }
        }
    };
    forms.controls = {
        label: "Controls",
        format: "table",
        fields: {
            controls: {
                layout: 'half',
                form: forms.control
            }
        }
    };


    forms.requestList = {
        label: "Request",
        requiredAttributes: ["objectId"],
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                altIcon: 'check',
                altCondition: ['edit'],
                function: 'newEntry',
                altFunction: 'checkEntry'
            },
            edit: {
                label: 'Edit',
                icon: 'edit',
                function: 'editEntry',
                condition: ['select']
            },
            cancel: {
                label: 'Cancel',
                icon: 'cancel',
                function: 'cancelEntry',
                condition: ['select', 'edit']
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteEntry',
                condition: ['select']
            }

        },

        fields: {
            approvalStatus: {
                layout: "one"
            },
            entitlementType: {
                layout: "one"
            },
            entitlementrel: {
                layout: "third"
            },
            requestorrel: {
                layout: "third"
            }
            /*
            ,
            referencerel : {
                layout : "third"
            }
            */
        }
    };
    forms.requestsList = {
        label: "Requests",
        fields: {
            requests: {
                layout: 'half',
                form: forms.requestList
            }
        }
    };


    forms.request = {
        label: "Requests",
        fields: {
            approvalStatus: {
                layout: "one"
            },
            entitlementType: {
                layout: "one"
            },
            entitlementrel: {
                layout: "third"
            },
            requestorrel: {
                layout: "third"
            }
        },
        forms: ["dateinfo", "attributes"]
    };



    forms.attributes = {
        label: "Attributes",
        format: "table",
        /*requiredAttributes : ["objectId"],*/
        fields: {
            attributes: {
                layout: 'full',
                form: forms.attribute
            }
        }
    };
    forms.ctlattributes = {
        label: "Controls and Attributes",
        /*requiredAttributes : ["objectId"],*/
        fields: {
            attributes: {
                layout: 'half',
                form: forms.attribute
            },
            controls: {
                /*requiredAttributes : ["objectId"],*/
                layout: "half",
                form: forms.control
            }
        }
    };
    forms.reqattributes = {
        label: "Requests and Attributes",
        /*requiredAttributes : ["objectId"],*/
        fields: {
            attributes: {
                layout: 'half',
                form: forms.attribute
            },
            requestsList: {
                requiredAttributes: ["objectId"],
                layout: "half",
                form: forms.requestList
            }
        }
    };



    forms.elementValues = {
        label: "Select Option",
        requiredAttributes: ["elementType"],
        requiredValues: ["SELECT|MULTIPLE_SELECT"],
        referField: true,
        commands: {
            new: {
                icon: 'add',
                altIcon: 'check',
                altCondition: ['edit'],
                function: 'newEntry',
                altFunction: 'checkEntry'
            },
            edit: {
                label: 'Edit',
                icon: 'edit',
                function: 'editEntry',
                condition: ['select']
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteEntry',
                condition: ['select']
            }

        },

        fields: {
            name: {
                layout: "third"
            },
            textValue: {
                layout: "third"
            }
        }
    };

    forms.dateinfo = {
        label: "Info",
        fields: {
            uri: {
                layout: "full"
            },
            urn: {
                layout: "third"
            },
            objectId: {
                layout: "third"
            },
            organizationPath: {
                layout: "third"
            },
            createdDate: {
                layout: "third"
            },
            modifiedDate: {
                layout: "third"
            },
            expiryDate: {
                layout: "third"
            }
        }
    };
    /*
    forms.subdateinfo = {
        label : "Info",
        fields : {
            uri : {
                layout : "half"
            },
            objectId : {
                layout : "half"
            },
            createdDate : {
                layout : "third"
            },
            modifiedDate : {
                layout : "third"
            },
            expiryDate : {
                layout : "third"
            }
        }
    };
    */
    forms.messageMeta = {
        label: "Message Meta",
        fields: {
            senderType: {
                layout: "one"
            },
            senderId: {
                layout: "third"
            },
            recipientType: {
                layout: "one"
            },
            recipientId: {
                layout: "third"
            },
            expires: {
                layout: "one"
            },
            uri: {
                layout: "half"
            },
            objectId: {
                layout: "third"
            },
            createdDate: {
                layout: "third"
            },
            modifiedDate: {
                layout: "third"
            },
            expiryDate: {
                layout: "third"
            },
            transportType: {
                layout: "one"
            },
            transportId: {
                layout: "third"
            },
            referenceType: {
                layout: "one"
            },
            referenceId: {
                layout: "third"
            }
        }
    };
    forms.message = {
        label: "Message",
        fields: {

            name: {
                layout: "half"
            },
            valueType: {
                layout: "half"
            },
            spoolBucketType: {
                layout: "third"
            },
            spoolBucketName: {
                layout: "third"
            },
            spoolStatus: {
                layout: "third"
            },
            data: {
                layout: "full"
            }
        },
        forms: ["messageMeta"]
    };


    forms.groupdateinfo = {
        label: "Info",
        fields: {
            uri: {
                layout: "full"
            },
            urn: {
                layout: "third"
            },
            objectId: {
                layout: "third"
            },
            organizationPath: {
                layout: "third"
            },
            createdDate: {
                layout: "third"
            },
            modifiedDate: {
                layout: "third"
            },
            expiryDate: {
                layout: "third"
            },
            groupPath: {
                layout: "half"
            }
        }
    };
    forms.groupinfo = {
        label: "Info",
        requiredAttributes: ["objectId"],
        fields: {
            uri: {
                layout: "full"
            },
            urn: {
                layout: "third"
            },
            objectId: {
                layout: "third"
            },
            organizationPath: {
                layout: "third"
            },
            groupPath: {
                layout: "half"
            }
        }
    }
    forms.lightgroupinfo = {
        label: "Info",
        requiredAttributes: ["objectId"],
        fields: {
            uri: {
                layout: "full"
            },
            objectId: {
                layout: "third"
            },
            organizationPath: {
                layout: "third"
            },
            groupPath: {
                layout: "third"
            }
        }
    }
    forms.parentinfo = {
        label: "Info",
        fields: {
            uri: {
                layout: "full"
            },
            urn: {
                layout: "third"
            },
            objectId: {
                layout: "third"
            },
            organizationPath: {
                layout: "third"
            },
            path: {
                layout: "half"
            }
        }
    }
    forms.grouptypeinfo = {
        label: "Info",
        fields: {
            uri: {
                layout: "full"
            },
            /*
            urn : {
                layout : "third"
            },
            */
            objectId: {
                layout: "third"
            },
            organizationPath: {
                layout: "third"
            },
            groupPath: {
                layout: "third"
            }
        }
    }

    am7model.models.push(
        {
            name: "executeExt",
            fields: [
                {
                    name: "execute",
                    label: 'Execute',
                    virtual: true,
                    ephemeral: true,
                    type: 'form',
                    function: 'objectExecute',
                    format: 'form'
                }
            ]
        }
    );


    /// TEMPORARY: Move this directly to the form config setting to override the model, versus modifying the model definition
    ///
    am7model.enums.mimeTypeEnumType = ["application/binhex", "application/book", "application/cdf", "application/clariscad", "application/commonground", "application/drafting", "application/envoy", "application/excel", "application/fractals", "application/freeloader", "application/futuresplash", "application/groupwise", "application/hlp", "application/hta", "application/i-deas", "application/inf", "application/json", "application/lha", "application/marc", "application/mbedlet", "application/mcad", "application/mime", "application/mspowerpoint", "application/msword", "application/mswrite", "application/netmc", "application/octet-stream", "application/oda", "application/pdf", "application/pkix-cert", "application/pkix-crl", "application/postscript", "application/ringing-tones", "application/sdp", "application/sea", "application/set", "application/sla", "application/smil", "application/solids", "application/sounder", "application/step", "application/streamingmedia", "application/toolbook", "application/vda", "application/vocaltec-media-desc", "application/vocaltec-media-file", "application/wordperfect", "application/x-aim", "application/x-authorware-bin", "application/x-authorware-map", "application/x-authorware-seg", "application/x-bcpio", "application/x-bsh", "application/x-bzip", "application/x-cdlink", "application/x-chat", "application/x-cocoa", "application/x-compressed", "application/x-conference", "application/x-cpio", "application/x-cpt", "application/x-deepv", "application/x-director", "application/x-dvi", "application/x-elc", "application/x-envoy", "application/x-esrehber", "application/x-excel", "application/x-freelance", "application/x-gsp", "application/x-gss", "application/x-gtar", "application/x-gzip", "application/x-hdf", "application/x-helpfile", "application/x-httpd-imap", "application/x-ima", "application/x-inventor", "application/x-java-class", "application/x-java-commerce", "application/x-javascript", "application/x-koan", "application/x-ksh", "application/x-latex", "application/x-livescreen", "application/x-lotus", "application/x-mif", "application/x-mix-transfer", "application/x-msexcel", "application/x-navi-animation", "application/x-navidoc", "application/x-navimap", "application/x-netcdf", "application/x-newton-compatible-pkg", "application/x-omc", "application/x-omcdatamaker", "application/x-omcregerator", "application/x-pagemaker", "application/x-pcl", "application/x-pixclscript", "application/x-project", "application/x-qpro", "application/x-seelogo", "application/x-shockwave-flash", "application/x-sprite", "application/x-stuffit", "application/x-tar", "application/x-tbook", "application/x-tcl", "application/x-tex", "application/x-texinfo", "application/x-troff", "application/x-troff-man", "application/x-troff-me", "application/x-troff-ms", "application/x-ustar", "application/x-visio", "application/x-wais-source", "application/x-wintalk", "application/x-world", "audio/aiff", "audio/basic", "audio/it", "audio/make", "audio/mid", "audio/midi", "audio/mod", "audio/mpeg", "audio/nspaudio", "audio/tsp-audio", "audio/tsplayer", "audio/voc", "audio/voxware", "audio/wav", "audio/weba", "audio/x-au", "audio/x-gsm", "audio/x-jam", "audio/x-liveaudio", "audio/x-mpequrl", "audio/x-pn-realaudio", "audio/x-pn-realaudio-plugin", "audio/x-psid", "audio/x-realaudio", "audio/x-twinvq", "audio/x-twinvq-plugin", "audio/xm", "chemical/x-pdb", "i-world/i-vrml", "image/bmp", "image/cmu-raster", "image/fif", "image/florian", "image/gif", "image/ief", "image/jpeg", "image/jutvision", "image/naplps", "image/pict", "image/png", "image/tiff", "image/x-dwg", "image/x-icon", "image/x-jg", "image/x-jps", "image/x-niff", "image/x-pcx", "image/x-pict", "image/x-portable-anymap", "image/x-portable-bitmap", "image/x-portable-graymap", "image/x-portable-pixmap", "image/x-quicktime", "image/x-rgb", "image/x-xpixmap", "image/x-xwindowdump", "image/xbm", "image/xpm", "model/iges", "model/vrml", "model/x-pov", "multipart/x-zip", "music/x-karaoke", "paleovu/x-pv", "text/asp", "text/css", "text/csv", "text/html", "text/mcf", "text/pascal", "text/plain", "text/richtext", "text/scriplet", "text/sgml", "text/tab-separated-values", "text/uri-list", "text/webviewhtml", "text/x-asm", "text/x-audiosoft-intra", "text/x-c", "text/x-component", "text/x-fortran", "text/x-h", "text/x-java-source", "text/x-la-asf", "text/x-m", "text/x-pascal", "text/x-script", "text/x-server-parsed-html", "text/x-setext", "text/x-speech", "text/x-uil", "text/x-uuencode", "text/x-vcalendar", "text/xml", "video/animaflex", "video/avi", "video/avs-video", "video/fli", "video/gl", "video/3gpp", "video/mp4", "video/mpeg", "video/quicktime", "video/vdo", "video/vivo", "video/webm", "video/webp", "video/x-amt-demorun", "video/x-amt-showrun", "video/x-dl", "video/x-dv", "video/x-flv", "video/x-isvideo", "video/x-motion-jpeg", "video/x-ms-asf", "video/x-ms-wmv", "video/x-qtc", "video/x-scm", "video/x-sgi-movie", "windows/metafile", "www/mime", "x-conference/x-cooltalk", "x-world/x-vrt", "xgl/drawing", "xgl/movie"];
    //let nameId = am7model.getModel("common.nameId");
    //nameId
    let sysId = am7model.getModel("system.primaryKey");
    sysId.fields.push({
        name: "uri",
        label: 'URI',
        type: 'link',
        virtual: true,
        ephemeral: true,
        format: 'object-link'
    },
        {
            name: "model",
            label: "Model",
            type: "string",
            virtual: true,
            ephemeral: true,
            function: 'getModel'
        }

    );


    let role = am7model.getModel("auth.role");
    role.fields.push({
        name: "members",
        label: 'Members',
        type: 'list',
        baseType: "model",
        baseModel: "$flex",
        function : 'objectMembers',
        promise: true,
        foreignType: "type",
        virtual: true,
        ephemeral: true,
        format: 'table'
    });

    let dataM = am7model.getModel("data.data");
    dataM.inherits.push("executeExt");
    //dataM.query.push("groupPath");
    let ctf = am7model.getModelField(dataM, "contentType");
    ctf.type = "enum";
    ctf.default = "text/plain";
    ctf.enum = am7model.enums.mimeTypeEnumType;
    ctf.filter = /(^text\/(plain|css|csv|html|xml)$|\/json$|javascript$|^video\/(webm|webp|mpeg|avi|mp4|3gpp)$|^audio\/(wav|mp3)|^image\/(gif|jpeg|png))/;

    let tagM = am7model.getModel("data.tag");
    let tagf = am7model.getModelField(tagM, "type");
    tagf.type = "enum";
    tagf.default = am7model.enums.modelNames[0];
    tagf.enum = am7model.enums.modelNames.sort();
    forms.data = {
        label: "Data",
        commands: {
            fact: {
                label: 'Fact',
                icon: 'fact_check',
                function: 'makeFact',
                requiredAttribute: "objectId"
            }
        },
        query: ["stream", "compressionType", "vaulted", "enciphered"],
        fields: {
            name: {
                label: "Name",
                layout: "half"
            },
            contentType: {
                layout: "half"
            },
            /*
            compressionType: {
                layout : "one",
                readOnly: true
            },
            
            vaulted: {
                layout: "one"
            },
            vaultId: {
                layout: "one"
            },
            */
            description: {
                layout: "full"
            },
            dataBytesStore: {
                layout: "full",
                dragAndDrop: true,
                format: "contentType"
            }
        },
        //["name", "description", "createdDate", "modifiedDate", "expiryDate", "mimeType", "dataBytesStore"],
        forms: ["groupdateinfo", "tags", "ctlattributes", "execute"]
    };

    forms.control = {
        label: "Control",
        fields: {
            controlType: {
                layout: "one"
            },
            action: {
                layout: "third"
            },
            referenceType: {
                layout: "one"
            },
            referenceId: {
                layout: "third"
            }
        },
        //["name", "description", "createdDate", "modifiedDate", "expiryDate", "mimeType", "dataBytesStore"],
        forms: ["info"]
    };

    forms.info = {
        label: "Info",
        fields: {
            uri: {
                layout: "full"
            },
            urn: {
                layout: "third"
            },
            objectId: {
                layout: "third"
            },
            organizationPath: {
                layout: "third"
            }
        }
    }

    forms.user = {
        label: "User",
        commands: {
            fact: {
                label: 'Fact',
                icon: 'fact_check',
                function: 'makeFact',
                requiredAttribute: "objectId"
            }
        },
        fields: {
            name: {
                layout: "third"
            },
            type: {
                layout: "third"
            },
            status: {
                layout: "third"
            }
        },
        forms: ["info", "contactinfo", "tags", "reqattributes"]
    };


    forms.person = {
        label: "Person",
        commands: {
            fact: {
                label: 'Fact',
                icon: 'fact_check',
                function: 'makeFact',
                requiredAttribute: "objectId"
            }
        },
        fields: {
            name: {
                layout: "half"
            },
            gender: {
                layout: 'one',
                field: {
                    label: 'Gender',
                    type: 'list',
                    limit: ['male', 'female', 'unisex']
                }
            },
            birthDate: {
                layout: "one"
            },
            description: {
                layout: "full"
            },
            title: {
                layout: "third"
            },
            prefix: {
                layout: "third"
            },
            suffix: {
                layout: "third"
            },
            firstName: {
                layout: "third"
            },
            middleName: {
                layout: "third"
            },
            lastName: {
                layout: "third"
            }
        },
        forms: ["contactinfo", "personrel", "groupinfo", "attributes"]
    };

    forms.account = {
        label: "Account",
        commands: {
            fact: {
                label: 'Fact',
                icon: 'fact_check',
                function: 'makeFact',
                requiredAttribute: "objectId"
            }
        },
        fields: {
            name: {
                layout: "third"
            },
            type: {
                layout: "third"
            },
            status: {
                layout: "third"
            }
        },
        forms: ["contactinfo", "groupinfo", "tags", "reqattributes"]
    };

    forms.childlist = {
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                function: 'addChild',
                properties: {
                    picker: true
                }
            },
            view: {
                label: 'View',
                icon: 'file_open',
                function: 'openEntity',
                condition: ['select']
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteChild',
                condition: ['select']
            }
        },
        fields: {
            name: {
                layout: "full"
            }
        }
    }

    forms.entitylist2 = {
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                function: 'addEntity',
                properties: {
                    picker: true
                }
            },
            view: {
                label: 'View',
                icon: 'file_open',
                function: 'openEntity',
                condition: ['select']
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteEntity',
                condition: ['select']
            }
        },
        fields: {
            objectId: {
                layout: "full"
            }
        }
    }

    forms.entitylist = {
        format: "table",
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                function: 'addEntity',
                properties: {
                    picker: true
                }
            },
            view: {
                label: 'View',
                icon: 'file_open',
                function: 'openEntity',
                condition: ['select']
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteEntity',
                condition: ['select']
            }
        },
        fields: {
            name: {
                layout: "full"
            }
        }
    }

    forms.memberlist = {
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                function: 'addMember',
                properties: {
                    picker: true
                }
            },
            view: {
                label: 'View',
                icon: 'file_open',
                function: 'openEntity',
                condition: ['select']
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteMember',
                condition: ['select']
            }
        },
        fields: {
            name: {
                layout: "full"
            }
        }
    }

    forms.memberlist2 = {
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                function: 'addMember',
                properties: {
                    picker: true
                }
            },
            view: {
                label: 'View',
                icon: 'file_open',
                function: 'openEntity',
                condition: ['select']
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteMember',
                condition: ['select']
            }
        },
        fields: {
            objectId: {
                layout: "full"
            }
        }
    }

    forms.contactinfo = {
        label: "Contact",
        requiredAttributes: ["objectId"],
        model: true,
        property: "contactInformation",
        fields: {
            contactInformation: {
                layout: "full",
                form: forms.contactInformation
            }
        }
    }


    forms.contactInformation = {
        label: "Contact",
        fields: {
            contacts: {
                layout: 'half',
                format: "table",
                form: forms.entitylist
            },
            addresses: {
                layout: 'half',
                format: "table",
                form: forms.entitylist
            }
        }
    };

    forms.address = {
        label: "Address",
        fields: {
            name: {
                layout: 'two-thirds',
            },
            locationType: {
                layout: 'one'
            },
            preferred: {
                layout: 'one'
            },
            street: {
                layout: 'full'
            },
            street2: {
                layout: 'full'
            },
            city: {
                layout: 'third'
            },
            state: {
                layout: 'third'
            },
            postalCode: {
                layout: 'third'
            },
            country: {
                layout: 'third'
            }



        },
        forms: ["groupinfo", "attributes"]
    };

    forms.contact = {
        label: "Contact",
        fields: {
            name: {
                layout: 'third'
            },
            contactType: {
                layout: 'third'
            },
            locationType: {
                layout: 'third'
            },
            preferred: {
                layout: 'one'
            },
            contactValue: {
                layout: 'fifth'
            },
            description: {
                layout: 'full'
            }
        },
        forms: ["groupinfo", "attributes"]
    };

    forms.personrel = {
        label: "Relations",
        fields: {
            partners: {
                layout: "third",
                form: forms.entitylist
            },
            dependents: {
                layout: "third",
                form: forms.entitylist
            },
            accounts: {
                layout: "third",
                form: forms.entitylist
            }
        }
    };
    forms.role = {
        label: "Role",
        commands: {
            fact: {
                label: 'Fact',
                icon: 'fact_check',
                function: 'makeFact',
                requiredAttribute: "objectId"
            }
        },
        fields: {
            name: {
                layout: "half"
            },
            type: {
                layout: "half"
            }
        },
        forms: ["parentinfo", "rolemembers", "attributes"]
    };

    forms.permission = {
        label: "Permission",
        commands: {
            fact: {
                label: 'Fact',
                icon: 'fact_check',
                function: 'makeFact',
                requiredAttribute: "objectId"
            }
        },
        fields: {
            name: {
                layout: "half"
            },
            type: {
                layout: "half"
            }
        },
        forms: ["parentinfo", "attributes"]
    };

    forms.event = {
        label: "Event",
        fields: {
            name: {
                layout: "third"
            },
            parent: {
                layout: "third"
            },
            location: {
                layout: "third"
            },
            eventType: {
                layout: "third"
            },
            startDate: {
                layout: "third"
            },
            endDate: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            childEvents: {
                layout: "third",
                form: forms.childlist
            },
            entryTraits: {
                layout: "third",
                form: forms.entitylist
            },
            exitTraits: {
                layout: "third",
                form: forms.entitylist
            }
        },
        forms: ["eventpart", "eventobj", "grouptypeinfo", "attributes"]
    };
    forms.eventpart = {
        label: "Participants",
        requiredAttributes: ["objectId"],
        fields: {
            actors: {
                layout: "one",
                form: forms.entitylist
            },
            orchestrators: {
                layout: "one",
                form: forms.entitylist
            },
            influencers: {
                layout: "one",
                form: forms.entitylist
            },
            observers: {
                layout: "one",
                form: forms.entitylist
            }

        }
    };
    forms.eventobj = {
        label: "Objects",
        requiredAttributes: ["objectId"],
        fields: {
            things: {
                layout: "half",
                form: forms.entitylist
            },
            groups: {
                layout: "half",
                form: forms.entitylist
            }
        }
    };
    forms.location = {
        label: "Location",
        fields: {
            name: {
                layout: "half"
            },
            geographyType: {
                layout: "one"
            },
            latitude: {
                layout: "one"
            },
            longitude: {
                layout: "one"
            },

            classification: {
                layout: "half"
            },
            parent: {
                layout: "half"
            },
            description: {
                layout: "full"
            }
        },
        forms: ["locationrel", "grouptypeinfo", "attributes"]
    };

    /// TEMPORARY: Move this directly to the form config setting to override the model, versus modifying the model definition
    ///
    let parent = am7model.getModel("common.parent");
    parent.fields.push({
        name: "parent",
        label: "Parent",
        type: "string",
        format: "picker",
        pickerType: "self",
        pickerProperty: {
            selected: "id",
            entity: "parentId"
        },
        virtual: true,
        ephemeral: true
    });

    forms.note = {
        label: "Note",
        fields: {
            name: {
                layout: "half"
            },
            parent: {
                layout: "half"
            },
            createdDate: {
                layout: "half"
            },
            modifiedDate: {
                layout: "half"
            },

            text: {
                layout: "full",
                format: "textarea"
            }
        },
        forms: ["grouptypeinfo", "attributes"]
    };
    forms.trait = {
        label: "Trait",
        fields: {
            name: {
                layout: "third"
            },
            traitType: {
                layout: "third"
            },
            alignmentType: {
                layout: "third"
            },
            description: {
                layout: "full"
            }
        },
        forms: ["grouptypeinfo", "attributes"]
    };

    forms.tag = {
        label: "Tag",
        format: "table",
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                function: 'addMember',
                properties: {
                    picker: true
                }
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteMember',
                condition: ['select']
            }
        },
        fields: {
            name: {
                layout: "half"
            },
            type: {
                layout: "half"
            }
        },
        forms: ["grouptypeinfo", "attributes"]
    };
    forms.locationrel = {
        label: "Relation",
        requiredAttributes: ["objectId"],
        fields: {
            childLocations: {
                layout: "third",
                form: forms.childlist
            },
            borders: {
                layout: "third",
                form: forms.entitylist
            },
            boundaries: {
                layout: "third",
                form: forms.entitylist
            }
        }
    };
    forms.notechildren = {
        label: "Children",
        requiredAttributes: ["objectId"],
        fields: {
            childNotes: {
                layout: "full",
                form: forms.childlist
            }
        }
    };
    forms.eventObject = {
        label: "Events",
        commands: {
            view: {
                label: 'View',
                icon: 'file_open',
                function: 'openEntity',
                condition: ['select']
            }
        },
        fields: {
            name: {
                layout: "half"
            },
            model: {
                layout: "half"
            }
        }
    };
    forms.rolemember = {
        label: "Member",
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                function: 'addMember',
                properties: {
                    picker: true,
                    typeAttribute: 'type'
                }
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteMember',
                condition: ['select']
            }
        },
        fields: {
            name: {
                layout: "half"
            },
            model: {
                layout: "half"
            }
            /*
            roleType: {
                layout : "half"
            }
            */
        }
    };

    forms.rolemembers = {
        label: "Members",
        requiredAttributes: ["objectId"],
        fields: {
            members: {
                layout: 'half',
                form: forms.rolemember
                /*
                field : {
                    label : 'Members',
                    name: "members",
                    virtual : true,
                    ephemeral: true,
                    type : 'list',
                    baseType: "model",
                    baseModel : '$flex',
                    foreignType: "type",
                    function : 'objectMembers',
                    typeAttribute : 'type',
                    foreign : true,
                    format : 'table'
                }
                */
            }
        }
    };

    forms.eventtags = {
        label: "Events and Tags",
        requiredAttributes: ["objectId"],
        fields: {
            tags: {
                layout: 'one',
                form: forms.tag
            },
            /*
            events : {
                layout : 'one',
                form : forms.eventObject
            },
            */
            actors: {
                layout: 'one',
                form: forms.eventObject
            },
            orchestrators: {
                layout: 'one',
                form: forms.eventObject
            },
            influencers: {
                layout: 'one',
                form: forms.eventObject
            },
            observers: {
                layout: 'one',
                form: forms.eventObject
            }
        }
    };

    forms.tags = {
        label: "Tags",
        requiredAttributes: [],
        format: "table",
        fields: {
            tags: {
                layout: 'full',
                form: forms.tag
            }
        }
    };

    forms.group = {
        label: "Group",
        commands: {
            fact: {
                label: 'Fact',
                icon: 'fact_check',
                function: 'makeFact',
                requiredAttribute: "objectId"
            }
        },
        fields: {
            name: {
                layout: "half"
            },
            type: {
                layout: "half"
            }
        },
        //["name", "description", "createdDate", "modifiedDate", "expiryDate", "mimeType", "dataBytesStore"],
        forms: ["parentinfo", "tags", "ctlattributes"]
    };

    forms.form = {
        label: "Form",
        fields: {
            name: {
                layout: "half"
            },
            description: {
                layout: "half"
            },
            isTemplate: {
                layout: "one"
            },
            isGrid: {
                layout: "one",
                requiredAttributes: ["isTemplate"],
                referField: true
            },
            viewTemplate: {
                layout: "third",
                requiredAttributes: ["!isTemplate"],
                referField: true
            },
            template: {
                layout: "third",
                requiredAttributes: ["!isTemplate"],
                referField: true
            },
            childForms: {
                layout: "half",
                form: forms.entitylist
            },
            elements: {
                layout: "half",
                form: forms.entitylist
            }
        },
        forms: ["grouptypeinfo", "attributes"]
    };

    forms.formelement = {
        label: "Form Element",
        fields: {
            name: {
                layout: "half"
            },
            description: {
                layout: "half"
            },
            elementName: {
                layout: "third"
            },
            elementLabel: {
                layout: "third"
            },
            elementType: {
                layout: "third"
            },
            viewTemplate: {
                layout: "half"
            },
            validationRule: {
                layout: "half"
            },
            elementValues: {
                layout: "full",
                form: forms.elementValues,
                requiredAttributes: ["elementType"],
                requiredValues: ["SELECT|MULTIPLE_SELECT"],
                referField: true
            }
        },
        forms: ["grouptypeinfo", "attributes"]
    };

    forms.validationRule = {
        label: "Validation Rule",
        fields: {
            name: {
                layout: "third"
            },
            validationType: {
                layout: "third"
            },
            description: {
                layout: "third"
            },
            isRuleSet: {
                layout: "third"
            },
            allowNull: {
                layout: "third"
            },
            comparison: {
                layout: "third",
                requiredAttributes: ["validationType"],
                referField: true,
                requiredValues: ["BOOLEAN"],
            },
            expression: {
                layout: "third"
            },
            replacementValue: {
                layout: "third",
                requiredAttributes: ["validationType"],
                referField: true,
                requiredValues: ["REPLACEMENT"],
            },
            errorMessage: {
                layout: "third"
            },
            rules: {
                layout: "full",
                form: forms.entitylist,
                requiredAttributes: ["isRuleSet"],
                referField: true
            }
        },
        forms: ["grouptypeinfo", "attributes"]
    };

    forms.policy = {
        label: "Policy Form",
        fields: {
            name: {
                layout: "full"
            },
            createdDate: {
                layout: "third"
            },
            modifiedDate: {
                layout: "third"
            },
            expiryDate: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            condition: {
                layout: "third"
            },
            enabled: {
                layout: "one"
            },
            decisionAge: {
                layout: "one"
            },
            order: {
                layout: "one"
            },
            score: {
                layout: "one"
            },
            rules: {
                layout: "full",
                form: forms.entitylist,
                requiredAttributes: [],
                referField: true
            }
        },
        forms: ["grouptypeinfo", "attributes", "evaluate"]
    };

    forms.rule = {
        label: "Rule Form",
        fields: {
            name: {
                layout: "full"
            },
            description: {
                layout: "full"
            },
            ruleType: {
                layout: "third"
            },
            condition: {
                layout: "third"
            },
            score: {
                layout: "one"
            },
            order: {
                layout: "one"
            },

            rules: {
                layout: "half",
                form: forms.entitylist,
                requiredAttributes: [],
                referField: true
            },

            patterns: {
                layout: "half",
                form: forms.entitylist,
                requiredAttributes: [],
                referField: true
            }
        },
        forms: ["grouptypeinfo", "attributes"]
    };

    forms.pattern = {
        label: "Pattern Form",
        fields: {
            name: {
                layout: "full"
            },
            description: {
                layout: "full"
            },
            type: {
                layout: "third"
            },
            operation: {
                layout: 'third',
                format: 'picker',
                label: "Operation",
                field: {
                    format: "picker",
                    pickerType: "policy.operation",
                    pickerProperty: {
                        selected: "{object}",
                        entity: "operation"
                    }
                }
            },
            score: {
                layout: "one"
            },
            order: {
                layout: "one"
            },
            fact: {
                layout: 'third',
                format: 'picker',
                label: "Fact",
                field: {
                    format: "picker",
                    pickerType: "policy.fact",
                    pickerProperty: {
                        selected: "{object}",
                        entity: "fact"
                    }
                }
            },
            comparator: {
                layout: "third"
            },
            match: {
                layout: 'third',
                format: 'picker',
                label: "Match",
                field: {
                    format: "picker",
                    pickerType: "policy.fact",
                    pickerProperty: {
                        selected: "{object}",
                        entity: "match"
                    }
                }
            },

        },
        forms: ["grouptypeinfo", "attributes"]
    };

    forms.fact = {
        label: "Fact Form",
        fields: {
            name: {
                layout: "full"
            },
            description: {
                layout: "full"
            },
            factType: {
                layout: "two"
            },
            modelType: {
                layout: "two"
            },
            sourceDataType: {
                layout: "one"
            },
            score: {
                layout: "one"
            },
            sourceUrn: {
                layout: "half"
            },
            sourceUrl: {
                layout: "half"
            },
            factData: {
                layout: "half"
            }
        },
        forms: ["grouptypeinfo", "attributes"]
    };

    forms.operation = {
        label: "Operation Form",
        fields: {
            name: {
                layout: "full"
            },
            description: {
                layout: "full"
            },
            score: {
                layout: "third"
            },
            operationType: {
                layout: "third"
            },
            operationrel: {
                layout: "third"
            }
        },
        forms: ["grouptypeinfo", "attributes"]
    };


    forms.function = {
        label: "Fact Form",
        fields: {
            name: {
                layout: "full"
            },
            description: {
                layout: "full"
            },
            functionType: {
                layout: "third"
            },
            order: {
                layout: "third"
            },
            score: {
                layout: "third"
            },
            sourceurnrel: {
                layout: "half"
            },
            sourceUrl: {
                layout: "half"
            }
        },
        forms: ["grouptypeinfo", "attributes"]
    };
    am7model.models.push({ name: "confirmation", fields: [{ name: "textData", type: "string" }] });
    forms.confirmation = {
        label: "Confirm",
        fields: {
            textData: {
                layout: "full"
            }
        }
    };

    am7model.models.push(
        {
            name: "userProfile", icon: "work", fields: [
                {
                    name: "profilePortrait",
                    label: "Portrait",
                    type: "string",
                    format: "picker",
                    pickerType: "data.data",
                    pickerProperty: {
                        selected: "objectId",
                        entity: "profilePortrait",
                        path: "~/Gallery"
                    },
                    virtual: true
                }
            ]
        }
    );
    forms.userProfile = {
        label: "Profile",
        fields: {
            profilePortrait: {
                layout: 'full',
                format: 'picker',
                label: "Portrait",
                field: {
                    format: "picker",
                    pickerType: "data.data",
                    pickerProperty: {
                        selected: "objectId",
                        entity: "profilePortrait",
                        path: "~/Gallery"
                    }
                }
            }
        },
        forms: []
    };


    forms.evaluaterel = {
        label: "Evaluate",
        requiredRoles: ["scriptExecutor"],
        requiredAttributes: ["objectId"],
        commands: {
            exec: {
                label: 'Evaluate',
                icon: 'run_circle',
                function: 'evaluate'
            }
        },
        fields: {
            evaluate: {
                layout: "full"
            }
        }
    };

    forms.evaluate = {
        label: "Evaluate",
        requiredRoles: ["scriptExecutor"],
        requiredAttributes: ["objectId"],
        fields: {
            evaluate: {
                layout: "full",
                form: forms.evaluaterel,
                properties: {
                    prepare: "define"
                }
            }
        }
    };

    forms.executerel = {
        label: "Execute",
        requiredRoles: ["scriptExecutor"],
        requiredAttributes: ["contentType", "objectId"],
        requiredValues: ["application/x-javascript"],
        referField: true,
        commands: {
            exec: {
                label: 'Execute',
                icon: 'run_circle',
                function: 'execute'
            }
        },
        fields: {
            execute: {
                layout: "full"
            }
        }
    };

    forms.execute = {
        label: "Execute",
        requiredRoles: ["scriptExecutor"],
        requiredAttributes: ["contentType", "objectId"],
        requiredValues: ["application/x-javascript"],
        referField: true,
        fields: {
            execute: {
                layout: "full",
                form: forms.executerel
            }
        }
    };


    forms.cost = {
        label: "Cost",
        fields: {
            name: {
                layout: "third"
            },
            currencyType: {
                layout: "third"
            },
            value: {
                layout: "third"
            }
        },
        forms: ["groupinfo", "attributes", "tags"]
    };

    forms.time = {
        label: "Time",
        fields: {
            name: {
                layout: "third"
            },
            basisType: {
                layout: "third"
            },
            value: {
                layout: "third"
            }
        },
        forms: ["groupinfo", "attributes", "tags"]
    };

    forms.estimate = {
        label: "Estimate",
        fields: {
            name: {
                layout: "half"
            },
            estimateType: {
                layout: "half"
            },
            description: {
                layout: "full"
            },
            time: {
                layout: "half"
            },
            cost: {
                layout: "half"
            }
        },
        forms: ["groupinfo", "attributes", "tags"]
    };
    forms.budget = {
        label: "Budget",
        fields: {
            name: {
                layout: "half"
            },
            budgetType: {
                layout: "half"
            },
            description: {
                layout: "full"
            },
            time: {
                layout: "half"
            },
            cost: {
                layout: "half"
            }
        },
        forms: ["groupinfo", "attributes", "tags"]
    };

    forms.schedule = {
        label: "Schedule",
        fields: {
            name: {
                layout: "third"
            },
            startTime: {
                layout: "third"
            },
            endTime: {
                layout: "third"
            },
            goals: {
                layout: "half",
                form: forms.entitylist
            }
        },
        forms: ["groupinfo", "attributes", "tags"]
    };
    forms.resource = {
        label: "Resource",
        fields: {
            name: {
                layout: "third"
            },
            resourceType: {
                layout: "third"
            },
            resourceDataId: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            utilization: {
                layout: "third"
            },
            estimate: {
                layout: "third"
            },
            schedule: {
                layout: "third"
            }
        },
        forms: ["groupinfo", "attributes", "ctlattributes"]
    };
    forms.artifact = {
        label: "Artifact",
        fields: {
            name: {
                layout: "third"
            },
            createdDate: {
                layout: "third"
            },
            artifactType: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            artifactDataId: {
                layout: "third"
            },
            previousTransitionId: {
                layout: "third"
            },
            nextTransitionId: {
                layout: "third"
            }

        },
        forms: ["groupinfo", "attributes", "tags"]
    };

    forms.requirement = {
        label: "Requirement",
        fields: {
            name: {
                layout: "third"
            },
            requirementType: {
                layout: "third"
            },
            requirementStatus: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            order: {
                layout: "third"
            },
            note: {
                layout: "third"
            },
            form: {
                layout: "third"
            }
        },
        forms: ["groupinfo", "attributes", "tags"]
    };


    forms.taskrel = {
        label: "Relationships",
        requiredAttributes: ["objectId"],
        fields: {
            childTasks: {
                layout: "third",
                form: forms.childlist
            },
            actualCost: {
                layout: "third",
                form: forms.entitylist
            },
            actualTime: {
                layout: "third",
                form: forms.entitylist
            }
        }
    };

    forms.taskacts = {
        label: "Activities",
        requiredAttributes: ["objectId"],
        fields: {
            requirements: {
                layout: "one",
                form: forms.entitylist
            },
            dependencies: {
                layout: "one",
                form: forms.entitylist
            },
            resources: {
                layout: "one",
                form: forms.entitylist
            },
            artifacts: {
                layout: "one",
                form: forms.entitylist
            },
            notes: {
                layout: "one",
                form: forms.entitylist
            },
            work: {
                layout: "one",
                form: forms.entitylist
            }
        }
    };


    forms.task = {
        label: "Task",
        fields: {
            name: {
                layout: "third"
            },
            parent: {
                layout: "third"
            },
            taskStatus: {
                layout: "one"
            },
            order: {
                layout: "one"
            },
            startDate: {
                layout: "third"
            },
            dueDate: {
                layout: "third"
            },
            completedDate: {
                layout: "third"
            },
            description: {
                layout: "full"
            }
        },
        forms: ["taskrel", "taskacts", "grouptypeinfo", "attributes", "tags"]
    };

    forms.ticket = {
        label: "Task",
        fields: {
            name: {
                layout: "third"
            },
            ticketStatus: {
                layout: "one"
            },
            priority: {
                layout: "one"
            },
            severity: {
                layout: "one"
            },
            description: {
                layout: "full"
            },
            assignedResource: {
                layout: "third"
            },
            estimate: {
                layout: "third"
            },
            actualTime: {
                layout: "one"
            },
            actualCost: {
                layout: "one"
            },
            createdDate: {
                layout: "third"
            },
            modifiedDate: {
                layout: "third"
            },
            dueDate: {
                layout: "third"
            },
            artifacts: {
                layout: "one",
                form: forms.entitylist
            },
            dependencies: {
                layout: "one",
                form: forms.entitylist
            },
            notes: {
                layout: "one",
                form: forms.entitylist
            },
            requiredResources: {
                layout: "third",
                form: forms.entitylist
            },
            forms: {
                layout: "one",
                form: forms.entitylist
            }

        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };

    forms.model = {
        label: "Model",
        fields: {
            name: {
                layout: "full"
            },
            description: {
                layout: "full"
            },
            cases: {
                layout: "one",
                form: forms.entitylist
            },
            requirements: {
                layout: "one",
                form: forms.entitylist
            },
            dependencies: {
                layout: "one",
                form: forms.entitylist
            },
            artifacts: {
                layout: "one",
                form: forms.entitylist
            },
            models: {
                layout: "one",
                form: forms.entitylist
            }
        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };

    forms.processstep = {
        label: "Process Step",
        fields: {
            name: {
                layout: "two-thirds"
            },
            order: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            goals: {
                layout: "third",
                form: forms.entitylist
            },
            budgets: {
                layout: "third",
                form: forms.entitylist
            },
            requirements: {
                layout: "third",
                form: forms.entitylist
            }
        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };

    forms.process = {
        label: "Process",
        fields: {
            name: {
                layout: "third"
            },
            order: {
                layout: "third"
            },
            iterates: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            steps: {
                layout: "half",
                form: forms.entitylist
            },
            budgets: {
                layout: "half",
                form: forms.entitylist
            }
        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };

    forms.methodology = {
        label: "Methodology",
        fields: {
            name: {
                layout: "full"
            },
            description: {
                layout: "full"
            },
            processes: {
                layout: "half",
                form: forms.entitylist
            },
            budgets: {
                layout: "half",
                form: forms.entitylist
            }
        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };

    forms.goal = {
        label: "Goal",
        fields: {
            name: {
                layout: "two-thirds"
            },
            order: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            budget: {
                layout: "third"
            },
            schedule: {
                layout: "third"
            },
            assigned: {
                layout: "third"
            },
            requirements: {
                layout: "third",
                form: forms.entitylist
            },
            dependencies: {
                layout: "third",
                form: forms.entitylist
            },
            cases: {
                layout: "third",
                form: forms.entitylist
            }
        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };
    forms.case = {
        label: "Case",
        fields: {
            name: {
                layout: "two-thirds"
            },
            caseType: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            actors: {
                layout: "third",
                form: forms.entitylist
            },
            prerequisites: {
                layout: "third",
                form: forms.entitylist
            },
            sequence: {
                layout: "one",
                form: forms.entitylist
            },
            diagrams: {
                layout: "one",
                form: forms.entitylist
            }
        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };

    forms.module = {
        label: "Module",
        fields: {
            name: {
                layout: "two-thirds"
            },
            moduleType: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            time: {
                layout: "half"
            },
            cost: {
                layout: "half"
            },
            artifacts: {
                layout: "half",
                form: forms.entitylist
            },
            work: {
                layout: "half",
                form: forms.entitylist
            }
        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };

    forms.lifecycle = {
        label: "Lifecycle",
        fields: {
            name: {
                layout: "full"
            },
            description: {
                layout: "full"
            },
            goals: {
                layout: "one",
                form: forms.entitylist
            },
            schedules: {
                layout: "one",
                form: forms.entitylist
            },
            budgets: {
                layout: "one",
                form: forms.entitylist
            },
            projects: {
                layout: "half",
                form: forms.entitylist
            }

        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };

    forms.project = {
        label: "Project",
        fields: {
            name: {
                layout: "two-thirds"
            },
            schedule: {
                layout: "third"
            },
            description: {
                layout: "full"
            },
            requirements: {
                layout: "third",
                form: forms.entitylist
            },
            modules: {
                layout: "third",
                form: forms.entitylist
            },
            stages: {
                layout: "third",
                form: forms.entitylist
            }
        },
        forms: ["projectassets", "grouptypeinfo", "attributes", "tags"]
    };

    forms.projectassets = {
        label: "Assets",
        fields: {
            dependencies: {
                layout: "third",
                form: forms.entitylist
            },
            artifacts: {
                layout: "third",
                form: forms.entitylist
            },
            blueprints: {
                layout: "third",
                form: forms.entitylist
            }
        }
    };
    forms.work = {
        label: "Work",
        fields: {
            name: {
                layout: "full"
            },
            description: {
                layout: "full"
            },
            tasks: {
                layout: "third",
                form: forms.entitylist
            },
            resources: {
                layout: "third",
                form: forms.entitylist
            },
            dependencies: {
                layout: "one",
                form: forms.entitylist
            },
            artifacts: {
                layout: "one",
                form: forms.entitylist
            }
        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };

    forms.stage = {
        label: "Stage",
        fields: {
            name: {
                layout: "two-thirds"
            },
            order: {
                layout: "third",
            },
            description: {
                layout: "full"
            },
            schedule: {
                layout: "half"
            },
            methodology: {
                layout: "half"
            },
            work: {
                layout: "half"
            },
            budget: {
                layout: "half"
            }
        },
        forms: ["grouptypeinfo", "attributes", "tags"]
    };

    async function pickProfile(object, inst, name) {
        //console.log("~/GalleryHome/Characters/" + inst.api.name());
        //inst.change("portrait");
        //inst.change("profile");
        // " + inst.api.name()
        console.warn("Need to update profile.portrait after picking, and/or fix the child object property not being updated");
        let n = "";
        let p = object.caller();
        let np = "";
        console.log(p.getInstance()?.entity?.groupPath);
        if (p && p.getInstance()?.entity?.name) {
            let pe = p.getInstance().entity;
            let gp = "/Gallery";
            let tp = pe.groupPath.substring(0, pe.groupPath.lastIndexOf("/"));

            n = "/" + pe.name;
            np = tp + gp + "/Characters" + n;
        }
        /// "~/GalleryHome/Characters" + n
        //page.components.picker.fieldPicker(inst, inst.formField("portrait")?.field, undefined, undefined, np, function (objectPage, inst, field, useName, data) {
        object.picker(inst.formField("portrait")?.field, undefined, undefined, np, function (objectPage, inst, field, useName, data) {
            if (p && p.getInstance()?.entity?.profile) {
                p.getInstance().entity.profile.portrait = data;
            }
        });
    };

    async function reimage(object, inst) {
        if (!inst || inst.model.name != "olio.charPerson") {
            console.warn("Invalid object instance");
            return;
        }

        let x = await m.request({ method: 'GET', url: am7client.base() + "/olio/" + inst.model.name + "/" + inst.api.objectId() + "/reimage/true", withCredentials: true });
        if (x && x != null) {
            inst.entity.profile.portrait = x;
            m.redraw();
        }
    }
    async function narrate(object, inst) {
        if (!inst || inst.model.name != "olio.charPerson") {
            console.warn("Invalid object instance");
            return;
        }

        let x = await m.request({ method: 'GET', url: am7client.base() + "/olio/" + inst.model.name + "/" + inst.api.objectId() + "/narrate", withCredentials: true });
        if (x && x != null) {
            inst.api.narrative(x);
            m.redraw();
        }
    }
    async function rollCharacter(object, inst) {
        if (!inst) {
            return;
        }
        let x = await m.request({ method: 'GET', url: am7client.base() + "/olio/roll", withCredentials: true });
        if (x && x != null) {
            object.mergeEntity(x);
        }
    }
    async function getSystemPrompt(object, inst) {
        if (!inst) {
            return;
        }
        let x = await m.request({ method: 'GET', url: am7client.base() + "/chat/prompt", withCredentials: true });
        if (x && x != null) {
            object.mergeEntity(x);
        }
    }

    forms.color = {
        label: "Color",
        fields: {
            name: {
                layout: "third"
            },
            hex: {
                layout: "one",
                format: "color"
            },
            red: {
                layout: "one",
                readOnly: true
            },
            green: {
                layout: "one",
                readOnly: true
            },
            blue: {
                layout: "one",
                readOnly: true
            }


        },
        forms: ["lightgroupinfo"]
    };

    forms.charPerson = {
        label: "Character",
        fields: {
            profile: {
                layout: "one",
                field: {
                    label: "Profile"
                },
                format: "image"
            },
            name: {
                layout: "one"
            },
            firstName: {
                layout: "one"
            },
            middleName: {
                layout: "one"
            },
            lastName: {
                layout: "one"
            },
            gender: {
                layout: 'one',
                field: {
                    label: 'Gender',
                    type: 'list',
                    limit: ['male', 'female', 'unisex']
                }
            },

            eyeColor: {
                layout: 'one',
                format: 'picker',
                label: "Eye Color",
                field: {
                    format: "picker",
                    pickerType: "data.color",
                    pickerProperty: {
                        selected: "{object}",
                        entity: "eyeColor"
                    }
                }
            },
            hairColor: {
                layout: 'one',
                format: 'picker',
                label: "Hair Color",
                field: {
                    format: "picker",
                    pickerType: "data.color",
                    pickerProperty: {
                        selected: "id",
                        selected: "{object}",
                        entity: "hairColor"
                    }
                }

            },
            hairStyle: {
                layout: 'one'
            },
            birthDate: {
                layout: "one"
            },
            age: {
                layout: "one"
            },
            blank: {
                layout: "one",
                format: "blank",
                field: {
                    label: "",
                    readOnly: true
                }
            },
            /*
            blank : {
                layout : "full",
                format: "blank",
                field: {
                    label: "",
                    readOnly: true
                }
            },
            */
            /*
             pickProfile: {
                 format: "button",
                 layout: "one",
                 icon: 'run_circle',
                 requiredAttributes : ["objectId"],
                 field: {
                     label: "Pick Profile",
                     command: pickProfile
                 }
             },
             */
            reimage: {
                format: "button",
                layout: "one",
                icon: 'run_circle',
                requiredAttributes: ["objectId"],
                field: {
                    label: "Reimage",
                    command: reimage
                }
            },
            narrate: {
                format: "button",
                layout: "one",
                icon: 'run_circle',
                requiredAttributes: ["objectId"],
                field: {
                    label: "Narrate",
                    command: narrate
                }
            },
            roll: {
                format: "button",
                layout: "one",
                icon: 'run_circle',
                requiredAttributes: ["!objectId"],
                field: {
                    label: "Roll",
                    command: rollCharacter
                }
            },
            description: {
                layout: "third",
            },
            trades: {
                layout: "third",
                format: "textlist"
            },
            race: {
                layout: "third",
                format: "textlist"
            }
        },
        forms: ["personalityRef", "statisticsRef", "storeRef", "narrativeRef", "profileRef", "groupinfo"]
    };

    forms.storeRef = {
        label: "Store",
        model: true,
        property: "store",
        fields: {
            store: {
                layout: "full",
                form: forms.store
            }
        }
    }


    forms.store = {
        label: "Store",
        fields: {
            apparel: {
                layout: 'half',
                format: "table",
                form: forms.entitylist
            },
            items: {
                layout: 'half',
                format: "table",
                form: forms.entitylist
            }
        }
    };

    let apparel = am7model.getModel("olio.apparel");
    apparel.fields.push({
        name: "designerRef",
        label: "Designer",
        type: "string",
        format: "picker",
        pickerType: "olio.charPerson",
        pickerProperty: {
            selected: "id",
            entity: "designer"
        },
        virtual: true,
        ephemeral: true
    });
    forms.apparel = {
        label: "Apparel",
        fields: {
            name: {
                layout: 'half'
            },
            type: {
                layout: 'one'
            },
            category: {
                layout: 'one'
            },
            gender: {
                layout: 'one',
                field: {
                    label: 'Gender',
                    type: 'list',
                    limit: ['male', 'female', 'unisex']
                }
            },
            inuse: {
                layout: 'one'
            },
            designerRef: {
                layout: 'third'
            },
            manufacturer: {
                layout: 'third'
            },
            wearables: {
                layout: 'full',
                format: "table",
                form: forms.entitylist
            }
        },
        forms: ["lightgroupinfo"]
    };

    forms.quality = {
        label: "Quality",
        fields: {
            unit: {
                layout: 'one'
            },
            valueAdjustment: {
                layout: 'one'
            },
            width: {
                layout: 'one'
            },
            height: {
                layout: 'one'
            },
            weight: {
                layout: 'one'
            },
            length: {
                layout: 'one'
            },
            opacity: {
                layout: 'one',
                format: 'range'
            },
            elasticity: {
                layout: 'one',
                format: 'range'
            },
            glossiness: {
                layout: 'one',
                format: 'range'
            },
            viscocity: {
                layout: 'one',
                format: 'range'
            },
            sliminess: {
                layout: 'one',
                format: 'range'
            },
            blank: {
                layout: "one",
                format: "blank",
                field: {
                    label: "",
                    readOnly: true
                }
            },
            smoothness: {
                layout: 'one',
                format: 'range'
            },
            hardness: {
                layout: 'one',
                format: 'range'
            },
            toughness: {
                layout: 'one',
                format: 'range'
            },
            defensive: {
                layout: 'one',
                format: 'range'
            },
            offensive: {
                layout: 'one',
                format: 'range'
            },
            range: {
                layout: 'one',
                format: 'range'
            },
            waterresistance: {
                layout: 'one',
                format: 'range'
            },
            heatresistance: {
                layout: 'one',
                format: 'range'
            },
            insulation: {
                layout: 'one',
                format: 'range'
            },
            skill: {
                layout: 'one',
                format: 'range'
            }
        },
        forms: ["lightgroupinfo"]
    };

    forms.item = {
        label: "Item",
        fields: {
            name: {
                layout: 'third'
            },
            quality: {
                layout: 'one',
                format: 'range'
            },
            category: {
                layout: 'one'
            },
            type: {
                layout: 'one'
            },
            inuse: {
                layout: 'one'
            },
            qualities: {
                layout: 'half',
                format: 'table',
                form: forms.entitylist2

            }
        },
        forms: ["lightgroupinfo"]
    };
    forms.wearable = {
        label: "Wearable",
        fields: {
            name: {
                layout: 'third'
            },
            level: {
                layout: 'third',
                label: "Level",
                field: {
                    type: "list",
                    limit: ["NONE", "INTERNAL", "UNDER", "ON", "BASE", "ACCENT", "SUIT", "GARNITURE", "ACCESSORY", "OVER", "OUTER", "FULL_BODY", "ENCLOSURE", "UNKNOWN"],
                    default: "BASE"
                }
            },
            gender: {
                layout: 'third',
                field: {
                    label: 'Gender',
                    type: 'list',
                    limit: ['male', 'female', 'unisex']
                }
            },
            category: {
                layout: 'one',
                label: "Category",
                field: {
                    default: "clothing"
                }
            },
            fabric: {
                label: "Fabric",
                layout: 'one'
            },
            color: {
                layout: 'third',
                format: 'picker',
                label: "Color",
                field: {
                    format: "picker",
                    pickerType: "data.color",
                    pickerProperty: {
                        selected: "{object}",
                        entity: "color"
                    }
                }
            },
            inuse: {
                layout: 'third'
            },
            qualities: {
                layout: 'half',
                format: 'table',
                autocreate: true,
                form: forms.entitylist2
            },
            location: {
                layout: "half",
                format: "textlist"
            }


        },
        forms: ["lightgroupinfo"]
    };
    forms.statisticsRef = {
        label: "Statistics",
        model: true,
        property: "statistics",
        fields: {
            statistics: {
                layout: "full",
                form: forms.statistics
            }
        }
    }


    forms.statistics = {
        label: "Statistics",
        fields: {
            physicalStrength: {
                layout: 'one',
                format: 'range'
            },
            physicalEndurance: {
                layout: 'one',
                format: 'range'
            },
            manualDexterity: {
                layout: 'one',
                format: 'range'
            },
            agility: {
                layout: 'one',
                format: 'range'
            },
            speed: {
                layout: 'one',
                format: 'range'
            },
            mentalStrength: {
                layout: 'one',
                format: 'range'
            },
            mentalEndurance: {
                layout: 'one',
                format: 'range'
            },
            intelligence: {
                layout: 'one',
                format: 'range'
            },
            wisdom: {
                layout: 'one',
                format: 'range'
            },
            perception: {
                layout: 'one',
                format: 'range'
            },
            creativity: {
                layout: 'one',
                format: 'range'
            },
            spirituality: {
                layout: 'one',
                format: 'range'
            },
            charisma: {
                layout: 'one',
                format: 'range'
            },
            luck: {
                layout: 'one',
                format: 'range'
            },
            magic: {
                layout: 'one',
                format: 'range',
                readOnly: true
            },
            science: {
                layout: 'one',
                format: 'range',
                readOnly: true
            },
            willpower: {
                layout: 'one',
                format: 'range',
                readOnly: true
            },
            reaction: {
                layout: 'one',
                format: 'range',
                readOnly: true
            },
            save: {
                layout: 'one',
                format: 'range',
                readOnly: true
            },
            health: {
                layout: 'one',
                format: 'range',
                readOnly: true
            },
            maximumHealth: {
                layout: 'one',
                format: 'range',
                readOnly: true
            }

        }
    };
    forms.narrativeRef = {
        label: "Narrative",
        model: true,
        property: "narrative",
        fields: {
            narrative: {
                layout: "full",
                form: forms.narrative
            }
        }
    }
    forms.personalityRef = {
        label: "Personality",
        model: true,
        property: "personality",
        fields: {
            personality: {
                layout: "full",
                form: forms.personality
            }
        }
    };

    forms.profileRef = {
        label: "Profile",
        model: true,
        property: "profile",
        fields: {
            profile: {
                layout: "full",
                form: forms.profile
            }
        }
    };

    forms.profile = {
        label: "Personality",
        fields: {
            portrait: {
                layout: 'one',
                format: 'image',
                field: {
                    label: "Profile",
                    pickerType: "data.data",
                    pickerProperty: {
                        selected: "{object}",
                        entity: "portrait"
                    }
                }
            },
            pickProfile: {
                format: "button",
                layout: "one",
                icon: 'run_circle',
                requiredAttributes: [],
                field: {
                    label: "Pick Profile",
                    command: pickProfile
                }
            },
        }
    };

    forms.personality = {
        label: "Personality",
        fields: {
            openness: {
                layout: 'one',
                format: 'range'
            },
            conscientiousness: {
                layout: 'one',
                format: 'range'
            },
            extraversion: {
                layout: 'one',
                format: 'range'
            },
            agreeableness: {
                layout: 'one',
                format: 'range'
            },
            neuroticism: {
                layout: 'one',
                format: 'range'
            },
            blank: {
                layout: "one",
                format: "blank",
                field: {
                    label: "",
                    readOnly: true
                }
            },
            machiavellianism: {
                layout: 'one',
                format: 'range'
            },
            narcissism: {
                layout: 'one',
                format: 'range'
            },
            psychopathy: {
                layout: 'one',
                format: 'range'
            },
            blank2: {
                layout: "half",
                format: "blank",
                field: {
                    label: "",
                    readOnly: true
                }
            },
            sloanKey: {
                layout: 'one',
                readOnly: true
            },
            sloanCardinal: {
                layout: 'one',
                readOnly: true

            },
            mbtiKey: {
                layout: 'one',
                readOnly: true
            },
            darkTriadKey: {
                layout: 'one',
                readOnly: true
            }
        }
    };

    forms.narrative = {
        label: "Narrative",
        fields: {
            physicalDescription: {
                layout: 'full',
                label: "Description",
                format: 'print'
            },
            statisticsDescription: {
                layout: 'full',
                label: "Statistics",
                format: 'print'
            },
            outfitDescription: {
                layout: 'full',
                label: "Current Outfit",
                format: 'print'
            },
            armamentDescription: {
                layout: 'full',
                label: "Armament",
                format: 'print'
            },
            alignmentDescription: {
                layout: 'full',
                label: "Alignment",
                format: 'print'
            },
            darkTriadDescription: {
                layout: 'full',
                label: "Dark Triad",
                format: 'print'
            },
            sloanDescription: {
                layout: 'full',
                label: "Sloan",
                format: 'print'
            },
            mbtiDescription: {
                layout: 'full',
                label: "Meyers-Briggs",
                format: 'print'
            },
            sdPrompt: {
                layout: 'full',
                label: "SD Prompt",
                format: 'print'
            },
            sdNegativePrompt: {
                layout: 'full',
                label: "SD Negative Prompt",
                format: 'print'
            },
            objectId: {
                layout: 'full',
                label: "Object Id",
                readOnly: true
            }



        }
    };

    /// TODO: This whole nonsense is a WIP while migrating from the old to new model definition, and the UI updates being made around those changes.
    let prc = am7model.getModel("olio.llm.promptRaceConfig");
    let prcr = am7model.getModelField(prc, "raceType");
    prcr.type = "list";
    prcr.limit = ["L", "W", "X", "Y", "Z", "R", "S"];
    forms.races = {
        form: forms.promptRaceConfig,
        standardUpdate: true
    };
    forms.promptRaceConfig = {
        label: "Race",
        format: "table",
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                altIcon: 'check',
                altCondition: ['edit'],
                function: 'newEntry',
                altFunction: 'checkEntry'
            },
            edit: {
                label: 'Edit',
                icon: 'edit',
                function: 'editEntry',
                condition: ['select']
            },
            cancel: {
                label: 'Cancel',
                icon: 'cancel',
                function: 'cancelEntry',
                condition: ['select', 'edit']
            }

        },

        fields: {
            raceType: {
                layout: "third",
                field: {
                    type: "list",
                    label: "Race",
                    limit: ["L", "W", "X", "Y", "Z", "R", "S"]
                }
            },
            race: {
                layout: "two-thirds",
                format: "textlist"
            }
        }
    };

    forms.promptConfig = {
        label: "Prompt",
        fields: {
            name: {
                layout: "one"
            },
            systemPrompt: {
                format: "button",
                layout: "one",
                icon: 'run_circle',
                requiredAttributes: ["!objectId"],
                field: {
                    label: "Load Default",
                    command: getSystemPrompt
                }
            },
            blank: {
                layout: "two-thirds",
                format: "blank",
                field: {
                    label: "",
                    readOnly: true
                }
            },
            setting: {
                layout: "full",
                format: "textlist"
            },
            scene: {
                layout: "full",
                format: "textlist"
            },
            races: {
                format: "table",
                layout: "full",
                form: forms.promptRaceConfig
            }
        },
        forms: ["systemPromptConfig", "assistantPromptConfig", "userPromptConfig", "groupinfo", "attributes"]
    };
    forms.systemPromptConfig = {
        label: "System Prompt",
        fields: {
            system: {
                layout: "full",
                format: "textlist"
            },
            systemCensorWarning: {
                layout: "full",
                format: "textlist"
            },
            systemNlp: {
                layout: "full",
                format: "textlist"
            },
            systemAnalyze: {
                layout: "full",
                format: "textlist"
            }
        },
        forms: []
    };
    forms.assistantPromptConfig = {
        label: "Assistant Prompt",
        fields: {
            assistant: {
                layout: "full",
                format: "textlist"
            },
            assistantCensorWarning: {
                layout: "full",
                format: "textlist"
            },
            assistantNlp: {
                layout: "full",
                format: "textlist"
            },
            assistantReminder: {
                layout: "full",
                format: "textlist"
            }
        },
        forms: []
    };

    forms.userPromptConfig = {
        label: "User Prompt",
        fields: {
            user: {
                layout: "full",
                format: "textlist"
            },
            userConsentPrefix: {
                layout: "full",
                format: "textlist"
            },
            userConsentRating: {
                layout: "full",
                format: "textlist"
            },
            userConsentNlp: {
                layout: "full",
                format: "textlist"
            },
            userAnalyze: {
                layout: "full",
                format: "textlist"
            },
            userReminder: {
                layout: "full",
                format: "textlist"
            }
        },
        forms: []
    };
    forms.chatOptionsRef = {
        label: "Options",
        model: true,
        property: "chatOptions",
        fields: {
            chatOptions: {
                layout: "full",
                form: forms.chatOptions
            }
        }
    }


    forms.chatOptions = {
        label: "Options",
        fields: {
            temperature: {
                layout: 'third',
                format: "range"
            },
            top_k: {
                layout: 'third',
                format: "range"
            },
            top_p: {
                layout: 'third',
                format: "range"
            },
            min_p: {
                layout: 'third',
                format: "range"
            },
            repeat_last_n: {
                layout: 'third',
                format: "range"
            },
            repeat_penalty: {
                layout: 'third',
                format: "range"
            }

        }
    };
    forms.chatConfig = {
        label: "Chat Configuration",
        fields: {
            name: {
                layout: "one"
            },
            model: {
                layout: "one",
                label: "Model Name"
            },
            analyzeModel: {
                layout: "one",
                label: "Analyze Model"
            },
            rating: {
                layout: "one",
                field: {
                    type: "list",
                    label: "Rating",
                    limit: ["E", "E10", "T", "M", "AO", "RC"]
                }
            },
            assist: {
                layout: "one"
            },
            prune: {
                layout: "one"
            },
            serverUrl:{
                layout: "third"
            },
            apiVersion: {
                layout: "one"
            },
            serviceType: {
                layout: "one",
                label: "Service Type",
                field:{
                    type: "list",
                    limit: ["OPENAI", "OLLAMA"]
                }
            },
            apiKey:{
                layout: "third",
                type: "password"
            },
            systemCharacter: {
                layout: 'third',
                format: 'picker',
                label: "System Character",
                field: {
                    format: "picker",
                    pickerType: "olio.charPerson",
                    pickerProperty: {
                        selected: "{object}",
                        entity: "systemCharacter"
                    }
                }
            },
            userCharacter: {
                layout: 'third',
                format: 'picker',
                label: "User Character",
                field: {
                    format: "picker",
                    pickerType: "olio.charPerson",
                    pickerProperty: {
                        selected: "{object}",
                        entity: "userCharacter"
                    }
                }
            },
            setting: {
                layout: "third",
                label: "Setting"
            },
            startMode: {
                layout: "one",
                field: {
                    label: 'Who Starts?',
                    type: 'list',
                    limit: ['none', 'system', 'user']
                }
            },
            scene: {
                layout: "third",
                label: "Scene"
            },
            includeScene: {
                layout: "one",
                label: "Include Scene"
            },
            useNLP: {
                layout: "one"
            },
            nlpCommand: {
                layout: "third",
                label: "Command"
            },

        },
        forms: ["episodectl", "chatOptionsRef", "groupinfo", "attributes"]

    };

    forms.episodes = {
        label: "Chat Configuration",
        format: "table",
        commands: {
            new: {
                label: 'New',
                icon: 'add',
                altIcon: 'check',
                altCondition: ['edit'],
                function: 'newEntry',
                altFunction: 'checkEntry'
            },
            edit: {
                label: 'Edit',
                icon: 'edit',
                function: 'editEntry',
                condition: ['select']
            },
            cancel: {
                label: 'Cancel',
                icon: 'cancel',
                function: 'cancelEntry',
                condition: ['select', 'edit']
            },
            delete: {
                label: 'Delete',
                icon: 'delete_outline',
                function: 'deleteEntry',
                condition: ['select']
            }

        },
        fields: {
            number: {
                layout: "one",
                label: "Number"
            },
            completed: {
                layout: "one",
                label: "Completed"
            },
            name: {
                layout: "one",
                label: "Name"
            },
            theme: {
                layout: "one",
                label: "Theme"
            },
            stages: {
                layout: "full",
                format: "textlist",
                label: "Stages"
            }
        }
    };
    forms.episodectl = {
        label: "Episodes",
        format: "table",
        fields: {
            episodes: {
                layout: 'full',
                form: forms.episodes
            }
        }
    };
    forms.charPersonRel = {
        label: "Relations",
        fields: {
            partners: {
                layout: "third",
                form: forms.entitylist
            },
            dependents: {
                layout: "third",
                form: forms.entitylist
            },
            accounts: {
                layout: "third",
                form: forms.entitylist
            }
        }
    };

    am7model.models.push({
        name: "imageView",
        icon: "image",
        label: "Image",
        fields: [
            { name: "image", type: "model", baseModel: "data.data" }
        ]
    });

    forms.imageView = {
        label: "Image",
        fields: {
            image: {
                layout: "full",
                format: "image"
            }
        }
    };


    am7model.forms = forms;
}());