/**
 * Access Requests feature — Self-service access request and approval workflow (Phase 9)
 * Allows users to request roles/groups/permissions and approvers to approve/deny.
 */
import m from 'mithril';
import { page } from '../core/pageClient.js';
import { am7client } from '../core/am7client.js';
import { layout, pageLayout } from '../router.js';

// ── State ───────────────────────────────────────────────────────────

let activeTab = "mine";
let requests = [];
let loading = false;
let error = null;

// New request form state
let showNewForm = false;
let requestType = "role";
let available = [];
let availableLoading = false;
let cart = [];
let justification = "";
let submitting = false;

// Status filter state
let statusFilter = "";

// Detail expansion state
let expandedId = null;

// Approval action state
let denyingId = null;
let denyReason = "";

// ── Status helpers ──────────────────────────────────────────────────

var statusConfig = {
	REQUEST: { label: "Requested", icon: "schedule", color: "text-blue-600 dark:text-blue-400", bg: "bg-blue-100 dark:bg-blue-900" },
	PENDING: { label: "Pending", icon: "hourglass_top", color: "text-yellow-600 dark:text-yellow-400", bg: "bg-yellow-100 dark:bg-yellow-900" },
	APPROVE: { label: "Approved", icon: "check_circle", color: "text-green-600 dark:text-green-400", bg: "bg-green-100 dark:bg-green-900" },
	DENY:    { label: "Denied", icon: "cancel", color: "text-red-600 dark:text-red-400", bg: "bg-red-100 dark:bg-red-900" },
	REMOVE:  { label: "Removed", icon: "delete", color: "text-gray-500", bg: "bg-gray-100 dark:bg-gray-800" }
};

function getStatusConfig(status) {
	var s = (status || "").toUpperCase();
	return statusConfig[s] || { label: s || "Unknown", icon: "help", color: "text-gray-500", bg: "bg-gray-100 dark:bg-gray-800" };
}

function formatDate(d) {
	if (!d) return "—";
	var dt = new Date(d);
	return dt.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

function entitlementLabel(req) {
	var ent = req.entitlement;
	if (ent && ent.name) return ent.name;
	var et = req.entitlementType || "";
	return et.split(".").pop() || "Unknown";
}

function entitlementTypeLabel(req) {
	var et = req.entitlementType || "";
	if (et.indexOf("role") >= 0) return "Role";
	if (et.indexOf("group") >= 0) return "Group";
	if (et.indexOf("permission") >= 0) return "Permission";
	return et.split(".").pop() || "—";
}

// ── WebSocket listener ──────────────────────────────────────────────

let wsListener = null;

function onAccessRequestWsUpdate(/* eventData */) {
	// Auto-refresh the request list when a WebSocket notification arrives
	loadRequests();
}

function registerWsListener() {
	if (!wsListener) {
		wsListener = page.onAccessRequestUpdate(onAccessRequestWsUpdate);
	}
}

function unregisterWsListener() {
	if (wsListener) {
		page.offAccessRequestUpdate(wsListener);
		wsListener = null;
	}
}

// ── API helpers ─────────────────────────────────────────────────────

async function loadRequests() {
	loading = true;
	error = null;
	try {
		var view = activeTab;
		var result = await am7client.accessRequestList(view, statusFilter || null, 0, 50);
		if (result && result.results) {
			requests = result.results;
		} else if (Array.isArray(result)) {
			requests = result;
		} else {
			requests = [];
		}
	} catch (e) {
		error = "Failed to load requests";
		requests = [];
	}
	loading = false;
	m.redraw();
}

async function loadAvailable() {
	availableLoading = true;
	try {
		var result = await am7client.accessRequestableList(requestType, 0, 100);
		if (result && result.results) {
			available = result.results;
		} else if (Array.isArray(result)) {
			available = result;
		} else {
			available = [];
		}
	} catch (e) {
		available = [];
		page.toast("error", "Failed to load available " + requestType + "s");
	}
	availableLoading = false;
	m.redraw();
}

function addToCart(item) {
	if (cart.some(function (c) { return c.id === item.id; })) return;
	cart.push(item);
}

function removeFromCart(item) {
	cart = cart.filter(function (c) { return c.id !== item.id; });
}

async function submitRequests() {
	if (cart.length === 0) {
		page.toast("error", "Add at least one item to your request");
		return;
	}
	submitting = true;
	m.redraw();

	var succeeded = 0;
	var failed = 0;
	for (var i = 0; i < cart.length; i++) {
		var item = cart[i];
		try {
			var entType;
			if (requestType === "role") entType = "auth.role";
			else if (requestType === "group") entType = "auth.group";
			else entType = "auth.permission";

			var body = {
				schema: "access.accessRequest",
				action: "add",
				entitlement: { id: item.id },
				entitlementType: entType,
				description: justification
			};
			await am7client.accessRequestSubmit(body);
			succeeded++;
		} catch (e) {
			failed++;
		}
	}

	submitting = false;
	if (succeeded > 0) {
		page.toast("info", succeeded + " request(s) submitted");
		cart = [];
		justification = "";
		showNewForm = false;
		await loadRequests();
	}
	if (failed > 0) {
		page.toast("error", failed + " request(s) failed to submit");
	}
	m.redraw();
}

async function approveRequest(req) {
	try {
		await am7client.accessRequestUpdate(req.objectId, { schema: "access.accessRequest", approvalStatus: "APPROVE" });
		page.toast("info", "Request approved");
		await loadRequests();
	} catch (e) {
		page.toast("error", "Failed to approve request");
	}
}

async function denyRequest(req) {
	if (!denyReason.trim()) {
		page.toast("error", "Please provide a reason for denial");
		return;
	}
	try {
		await am7client.accessRequestUpdate(req.objectId, { schema: "access.accessRequest", approvalStatus: "DENY", description: denyReason });
		page.toast("info", "Request denied");
		denyingId = null;
		denyReason = "";
		await loadRequests();
	} catch (e) {
		page.toast("error", "Failed to deny request");
	}
}

async function remindApprover(req) {
	try {
		var result = await am7client.accessRequestNotify(req.objectId);
		if (result) {
			page.toast("info", "Reminder sent");
		} else {
			page.toast("error", "Failed to send reminder");
		}
	} catch (e) {
		page.toast("error", "Failed to send reminder");
	}
}

// ── View components ─────────────────────────────────────────────────

function renderTabs() {
	var tabs = [
		{ id: "mine", label: "My Requests" },
		{ id: "pending", label: "Pending My Approval" },
		{ id: "all", label: "All Requests" }
	];
	return m("div", { class: "flex items-center gap-1 mb-4 border-b dark:border-gray-700" }, [
		tabs.map(function (tab) {
			var active = activeTab === tab.id;
			return m("button", {
				key: tab.id,
				class: "px-4 py-2 text-sm font-medium transition-colors " +
					(active
						? "border-b-2 border-blue-500 text-blue-600 dark:text-blue-400"
						: "text-gray-500 hover:text-gray-700 dark:hover:text-gray-300"),
				onclick: function () {
					activeTab = tab.id;
					loadRequests();
				}
			}, tab.label);
		}),
		// Status filter dropdown
		m("div", { class: "ml-auto pb-1" },
			m("select", {
				class: "text-xs border dark:border-gray-600 rounded px-2 py-1 bg-transparent",
				value: statusFilter,
				onchange: function (e) {
					statusFilter = e.target.value;
					loadRequests();
				}
			}, [
				m("option", { value: "" }, "Open (default)"),
				m("option", { value: "APPROVE" }, "Approved"),
				m("option", { value: "DENY" }, "Denied"),
				m("option", { value: "PENDING" }, "Pending"),
				m("option", { value: "REMOVE" }, "Removed")
			])
		)
	]);
}

function renderRequestTable() {
	if (loading) return m("div", { class: "text-sm text-gray-500 py-4" }, "Loading...");
	if (requests.length === 0) return m("div", { class: "text-sm text-gray-500 italic py-4" }, "No requests found.");

	return m("table", { class: "w-full text-sm" }, [
		m("thead", m("tr", { class: "border-b dark:border-gray-700" }, [
			m("th", { class: "text-left py-2 pr-4 w-4" }, ""),
			m("th", { class: "text-left py-2 pr-4" }, "Status"),
			m("th", { class: "text-left py-2 pr-4" }, "Entitlement"),
			m("th", { class: "text-left py-2 pr-4" }, "Type"),
			m("th", { class: "text-left py-2 pr-4" }, "Date"),
			m("th", { class: "text-left py-2" }, "Actions")
		])),
		m("tbody", requests.map(function (req) {
			var sc = getStatusConfig(req.approvalStatus);
			var isExpanded = expandedId === req.objectId;
			return [
				m("tr", {
					key: req.objectId,
					class: "border-b dark:border-gray-800 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800/50",
					onclick: function () { expandedId = isExpanded ? null : req.objectId; }
				}, [
					m("td", { class: "py-2 pr-1" },
						m("span", { class: "material-symbols-outlined text-sm text-gray-400 transition-transform " + (isExpanded ? "rotate-90" : "") }, "chevron_right")
					),
					m("td", { class: "py-2 pr-4" },
						m("span", { class: "inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium " + sc.bg + " " + sc.color }, [
							m("span", { class: "material-symbols-outlined text-sm" }, sc.icon),
							sc.label
						])
					),
					m("td", { class: "py-2 pr-4 font-medium" }, entitlementLabel(req)),
					m("td", { class: "py-2 pr-4 text-gray-500" }, entitlementTypeLabel(req)),
					m("td", { class: "py-2 pr-4 text-gray-500" }, formatDate(req.createdDate)),
					m("td", { class: "py-2", onclick: function (e) { e.stopPropagation(); } }, renderActions(req))
				]),
				// Expanded detail row
				isExpanded ? m("tr", { key: req.objectId + "-detail", class: "border-b dark:border-gray-800 bg-gray-50 dark:bg-gray-800/30" }, [
					m("td", { colspan: 6, class: "py-3 px-4" }, renderRequestDetail(req))
				]) : null,
				// Deny reason input row
				denyingId === req.objectId ? m("tr", { key: req.objectId + "-deny", class: "border-b dark:border-gray-800" }, [
					m("td", { colspan: 6, class: "py-2" },
						m("div", { class: "flex gap-2 items-center" }, [
							m("input", {
								type: "text",
								class: "flex-1 text-field-full text-sm",
								placeholder: "Reason for denial",
								value: denyReason,
								oninput: function (e) { denyReason = e.target.value; },
								onkeydown: function (e) { if (e.key === "Enter") denyRequest(req); }
							}),
							m("button", {
								class: "btn btn-primary text-xs px-3 py-1",
								onclick: function () { denyRequest(req); }
							}, "Confirm Deny"),
							m("button", {
								class: "text-gray-500 hover:text-gray-700 text-xs px-2 py-1",
								onclick: function () { denyingId = null; denyReason = ""; }
							}, "Cancel")
						])
					)
				]) : null
			];
		}))
	]);
}

function renderActions(req) {
	var status = (req.approvalStatus || "").toUpperCase();

	if (activeTab === "pending" && status === "REQUEST") {
		return m("div", { class: "flex gap-2" }, [
			m("button", {
				class: "text-green-600 hover:text-green-800 text-sm font-medium",
				onclick: function () { approveRequest(req); }
			}, "Approve"),
			m("button", {
				class: "text-red-500 hover:text-red-700 text-sm font-medium",
				onclick: function () { denyingId = req.objectId; denyReason = ""; }
			}, "Deny")
		]);
	}

	if (activeTab === "mine" && status === "REQUEST") {
		return m("button", {
			class: "text-blue-500 hover:text-blue-700 text-sm",
			onclick: function () { remindApprover(req); }
		}, "Remind");
	}

	return null;
}

function renderRequestDetail(req) {
	var requester = req.requester;
	var approverObj = req.approver;
	var subject = req.subject;
	return m("div", { class: "grid grid-cols-2 gap-x-6 gap-y-2 text-xs" }, [
		// Left column: Request info
		m("div", [
			m("div", { class: "font-medium text-gray-600 dark:text-gray-400 mb-1" }, "Request Details"),
			m("div", { class: "flex gap-2 mb-1" }, [
				m("span", { class: "text-gray-500" }, "Action:"),
				m("span", (req.action || "add").toUpperCase())
			]),
			m("div", { class: "flex gap-2 mb-1" }, [
				m("span", { class: "text-gray-500" }, "Requester:"),
				m("span", requester && requester.name ? requester.name : "—")
			]),
			m("div", { class: "flex gap-2 mb-1" }, [
				m("span", { class: "text-gray-500" }, "Subject:"),
				m("span", subject && subject.name ? subject.name : (requester && requester.name ? requester.name : "—"))
			]),
			m("div", { class: "flex gap-2 mb-1" }, [
				m("span", { class: "text-gray-500" }, "Approver:"),
				m("span", approverObj && approverObj.name ? approverObj.name : "Not assigned")
			]),
			req.description ? m("div", { class: "mt-2" }, [
				m("span", { class: "text-gray-500" }, "Justification:"),
				m("div", { class: "mt-0.5 italic text-gray-700 dark:text-gray-300" }, req.description)
			]) : null
		]),
		// Right column: Timeline / spool messages
		m("div", [
			m("div", { class: "font-medium text-gray-600 dark:text-gray-400 mb-1" }, "Timeline"),
			m("div", { class: "flex gap-2 mb-1" }, [
				m("span", { class: "text-gray-500" }, "Created:"),
				m("span", formatDate(req.createdDate))
			]),
			req.modifiedDate ? m("div", { class: "flex gap-2 mb-1" }, [
				m("span", { class: "text-gray-500" }, "Updated:"),
				m("span", formatDate(req.modifiedDate))
			]) : null,
			req.messages && req.messages.length > 0 ? m("div", { class: "mt-2" }, [
				m("div", { class: "text-gray-500 mb-1" }, "Messages:"),
				req.messages.map(function (msg) {
					return m("div", { key: msg.objectId || msg.id, class: "flex gap-2 py-0.5 border-l-2 border-gray-300 dark:border-gray-600 pl-2 mb-1" }, [
						m("span", { class: "text-gray-500" }, formatDate(msg.createdDate)),
						m("span", msg.name || "Notification")
					]);
				})
			]) : m("div", { class: "text-gray-400 italic mt-1" }, "No spool messages")
		])
	]);
}

function renderNewRequestForm() {
	if (!showNewForm) return null;

	return m("div", { class: "mb-6 p-4 border border-gray-200 dark:border-gray-700 rounded" }, [
		m("div", { class: "flex justify-between items-center mb-3" }, [
			m("h3", { class: "text-lg font-medium" }, "New Access Request"),
			m("button", {
				class: "text-gray-400 hover:text-gray-600 text-sm",
				onclick: function () { showNewForm = false; cart = []; }
			}, "Cancel")
		]),

		// Type selector
		m("div", { class: "mb-3" }, [
			m("label", { class: "block text-sm mb-1" }, "Request Type"),
			m("select", {
				class: "text-field-full text-sm",
				value: requestType,
				onchange: function (e) {
					requestType = e.target.value;
					cart = [];
					loadAvailable();
				}
			}, [
				m("option", { value: "role" }, "Role"),
				m("option", { value: "group" }, "Group"),
				m("option", { value: "permission" }, "Permission")
			])
		]),

		// Two-column: Available + Cart
		m("div", { class: "flex gap-4 mb-3" }, [
			// Available list
			m("div", { class: "flex-1" }, [
				m("label", { class: "block text-sm mb-1 font-medium" }, "Available"),
				m("div", { class: "border dark:border-gray-700 rounded max-h-48 overflow-y-auto" },
					availableLoading ? m("div", { class: "p-2 text-sm text-gray-500" }, "Loading...") :
					available.length === 0 ? m("div", { class: "p-2 text-sm text-gray-500 italic" }, "None found") :
					available.map(function (item) {
						var inCart = cart.some(function (c) { return c.id === item.id; });
						return m("div", {
							key: item.id || item.objectId,
							class: "flex items-center justify-between px-3 py-1.5 border-b dark:border-gray-800 last:border-0 text-sm cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-800 " + (inCart ? "bg-blue-50 dark:bg-blue-900/30" : ""),
							onclick: function () { if (!inCart) addToCart(item); }
						}, [
							m("span", item.name || "Unnamed"),
							inCart ? m("span", { class: "text-blue-500 text-xs" }, "Added") :
								m("span", { class: "text-gray-400 text-xs" }, "+")
						]);
					})
				)
			]),

			// Cart
			m("div", { class: "flex-1" }, [
				m("label", { class: "block text-sm mb-1 font-medium" }, "Cart (" + cart.length + ")"),
				m("div", { class: "border dark:border-gray-700 rounded max-h-48 overflow-y-auto" },
					cart.length === 0 ? m("div", { class: "p-2 text-sm text-gray-500 italic" }, "Select items from the list") :
					cart.map(function (item) {
						return m("div", {
							key: item.id || item.objectId,
							class: "flex items-center justify-between px-3 py-1.5 border-b dark:border-gray-800 last:border-0 text-sm"
						}, [
							m("span", item.name || "Unnamed"),
							m("button", {
								class: "text-red-400 hover:text-red-600 text-xs ml-2",
								onclick: function () { removeFromCart(item); }
							}, "Remove")
						]);
					})
				)
			])
		]),

		// Justification
		m("div", { class: "mb-3" }, [
			m("label", { class: "block text-sm mb-1" }, "Justification"),
			m("textarea", {
				class: "w-full text-field-full text-sm",
				rows: 3,
				placeholder: "Why do you need this access?",
				value: justification,
				oninput: function (e) { justification = e.target.value; }
			})
		]),

		// Submit
		m("button", {
			class: "btn btn-primary px-4 py-2 text-sm",
			disabled: cart.length === 0 || submitting,
			onclick: submitRequests
		}, submitting ? "Submitting..." : "Submit Request (" + cart.length + ")")
	]);
}

// ── Main view ───────────────────────────────────────────────────────

var accessRequestsView = {
	oninit: function () {
		activeTab = "mine";
		statusFilter = "";
		expandedId = null;
		registerWsListener();
		loadRequests();
	},
	onremove: function () {
		unregisterWsListener();
	},
	view: function () {
		return m("div", { class: "p-4 max-w-4xl" }, [
			// Header
			m("div", { class: "flex justify-between items-center mb-4" }, [
				m("h2", { class: "text-xl font-semibold" }, [
					m("span", { class: "material-symbols-outlined text-xl align-middle mr-2" }, "switch_access_shortcut"),
					"Access Requests"
				]),
				!showNewForm ? m("button", {
					class: "btn btn-primary px-4 py-2 text-sm",
					onclick: function () {
						showNewForm = true;
						loadAvailable();
					}
				}, "+ New Request") : null
			]),

			// New request form
			renderNewRequestForm(),

			// Tabs
			renderTabs(),

			// Request table
			renderRequestTable(),

			// Error
			error ? m("div", { class: "mt-3 text-red-500 text-sm" }, error) : null
		]);
	}
};

// ── Route export ────────────────────────────────────────────────────

export const routes = {
	"/accessRequests": {
		oninit: function () { accessRequestsView.oninit(); },
		view: function () { return layout(pageLayout(accessRequestsView.view())); }
	}
};
