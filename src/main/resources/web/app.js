// ── DOM refs ────────────────────────────────────────────────────────────
const messagesContainer = document.getElementById("messages-container");
const inputEl = document.getElementById("input");
const sendEl = document.getElementById("send");
const statusEl = document.getElementById("status");
const acEl = document.getElementById("autocomplete");
const membersEl = document.getElementById("members");
const jumpBottomEl = document.getElementById("jump-bottom");
const tabListEl = document.getElementById("tab-list");
const newTabBtn = document.getElementById("new-tab");
const sessionModal = document.getElementById("session-modal");
const sessionMdFile = document.getElementById("session-md-file");
const sessionOpenBtn = document.getElementById("session-open");
const sessionCancelBtn = document.getElementById("session-cancel");
const accessModal = document.getElementById("access-modal");
const accessDeviceName = document.getElementById("access-device-name");
const accessCode = document.getElementById("access-code");
const accessClaimBtn = document.getElementById("access-claim");
const accessRetryBtn = document.getElementById("access-retry");
const accessError = document.getElementById("access-error");

// ── Global (shared) state ───────────────────────────────────────────────
let agents = [];
let owner = false;
let acItems = [];
let acIndex = 0;

// ── Per-session state ───────────────────────────────────────────────────
// Each session has its own WebSocket, message list, and DOM container.
const SCROLL_NEAR_BOTTOM_PX = 80;

class Session {
    constructor(id, name, transcriptPath, contextMdPath) {
        this.id = id;
        this.name = name;
        this.transcriptPath = transcriptPath;
        this.contextMdPath = contextMdPath || null;
        this.ws = null;
        this.seenMessageIds = new Set();
        this.msgEls = {};            // message id → body DOM element
        this.el = null;              // the .session-msgs div in #messages-container
        this.inputDraft = "";        // saved input content when switching away
        this.inputHistory = [];      // per-session input history
        this.inputHistoryIndex = -1;
        this.inputHistoryDraft = "";
        this.scrollTop = 0;          // saved scroll position
        this.connected = false;
    }

    /** Whether this session has a read-only context MD file. */
    hasContext() {
        return !!this.contextMdPath;
    }

    isNearBottom() {
        const el = this.el;
        if (!el) return true;
        return el.scrollHeight - el.scrollTop - el.clientHeight < SCROLL_NEAR_BOTTOM_PX;
    }

    scrollToBottomIfNear(wasNearBottom) {
        const el = this.el;
        if (!el) return;
        const shouldStick = arguments.length > 0 ? wasNearBottom : this.isNearBottom();
        if (shouldStick) {
            el.scrollTop = el.scrollHeight;
            jumpBottomEl.classList.add("hidden");
        } else {
            jumpBottomEl.classList.remove("hidden");
        }
    }

    connect() {
        if (this.ws && (this.ws.readyState === WebSocket.OPEN
                || this.ws.readyState === WebSocket.CONNECTING)) {
            return;
        }
        const wsScheme = location.protocol === "https:" ? "wss://" : "ws://";
        const url = wsScheme + location.host + "/ws?session=" + encodeURIComponent(this.id);
        this.ws = new WebSocket(url);
        this.ws.onopen = () => {
            this.connected = true;
            if (sessionMgr.current === this) setStatus("online", "online");
            updateTabState(this);
        };
        this.ws.onclose = () => {
            this.connected = false;
            if (sessionMgr.current === this) setStatus("offline", "offline");
            updateTabState(this);
            // Reconnect after 1.5s
            setTimeout(() => { if (sessionMgr.sessions.has(this.id)) this.connect(); }, 1500);
        };
        this.ws.onmessage = (ev) => {
            const env = JSON.parse(ev.data);
            if (env.kind === "message") this.renderMessage(env.message);
            else if (env.kind === "append") this.appendToMessage(env.id, env.delta);
            else if (env.kind === "replace") this.replaceMessage(env.id, env.content);
            else if (env.kind === "roster") applyRoster(env.data);
        };
    }

    disconnect() {
        if (this.ws) {
            this.ws.onclose = null; // prevent reconnect timer
            this.ws.close();
            this.ws = null;
        }
        this.connected = false;
    }

    // ── Message rendering (per-session DOM) ─────────────────────────

    renderMessage(m) {
        if (m.id) {
            if (this.seenMessageIds.has(m.id)) return;
            this.seenMessageIds.add(m.id);
        }
        const div = document.createElement("div");
        const typeClass = (m.type || "agent").toLowerCase();
        div.className = "msg " + typeClass;
        if (typeClass === "agent" || typeClass === "synthesis") {
            div.classList.add("streaming");
        }
        const meta = document.createElement("div");
        meta.className = "meta";
        const metaLabel = document.createElement("span");
        metaLabel.textContent = (m.senderName || m.sender) + " · " + new Date(m.timestamp).toLocaleTimeString();
        meta.appendChild(metaLabel);
        if (typeClass === "agent" || typeClass === "synthesis") {
            const dlBtn = document.createElement("button");
            dlBtn.className = "dl-btn";
            dlBtn.textContent = "📥";
            dlBtn.title = "Download this response as Markdown";
            dlBtn.onclick = () => downloadMarkdown(body ? body.dataset.rawMd : m.content, m.senderName || m.sender);
            meta.appendChild(dlBtn);
        }
        const body = document.createElement("div");
        body.className = "body";
        body.innerHTML = renderMarkdown(m.content || "");
        body.dataset.rawMd = m.content || "";
        div.appendChild(meta);
        div.appendChild(body);
        const wasNearBottom = this.isNearBottom();
        this.el.appendChild(div);
        this.scrollToBottomIfNear(wasNearBottom);
        if (m.id) this.msgEls[m.id] = body;
    }

    appendToMessage(id, delta) {
        const body = this.msgEls[id];
        if (!body) return;
        const wasNearBottom = this.isNearBottom();
        body.dataset.rawMd = (body.dataset.rawMd || "") + delta;
        body.innerHTML = renderMarkdown(body.dataset.rawMd);
        this.scrollToBottomIfNear(wasNearBottom);
    }

    replaceMessage(id, content) {
        const body = this.msgEls[id];
        if (!body) return;
        const wasNearBottom = this.isNearBottom();
        body.innerHTML = renderMarkdown(content || "");
        body.dataset.rawMd = content || "";
        const msgEl = body.closest(".msg");
        if (msgEl) msgEl.classList.remove("streaming");
        this.scrollToBottomIfNear(wasNearBottom);
    }

    // ── Input history (per-session) ─────────────────────────────────

    addToHistory(content) {
        if (this.inputHistory.length === 0 ||
            this.inputHistory[this.inputHistory.length - 1] !== content) {
            this.inputHistory.push(content);
        }
    }

    navigateHistory(delta) {
        if (this.inputHistory.length === 0) return;
        if (this.inputHistoryIndex === -1) {
            this.inputHistoryDraft = inputEl.value;
        }
        const newIndex = this.inputHistoryIndex + delta;
        if (newIndex < -1) return;
        if (newIndex >= this.inputHistory.length) {
            this.inputHistoryIndex = -1;
            inputEl.value = this.inputHistoryDraft;
            autoResize();
            return;
        }
        this.inputHistoryIndex = newIndex;
        if (this.inputHistoryIndex === -1) {
            inputEl.value = this.inputHistoryDraft;
        } else {
            inputEl.value = this.inputHistory[this.inputHistory.length - 1 - this.inputHistoryIndex];
        }
        autoResize();
    }
}

// ── Session Manager ──────────────────────────────────────────────────────

const sessionMgr = {
    sessions: new Map(),
    current: null,
    tabOrder: [],

    /** Open or switch to a session by id. Creates it on the backend if needed. */
    async open(id, name, mdFile) {
        // If already open, just switch
        let session = this.sessions.get(id);
        if (session) {
            this.switchTo(id);
            return session;
        }
        // Tell backend to create/open the session
        let info;
        try {
            const res = await fetch("/api/sessions", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(mdFile ? { mdFile } : { sessionId: id })
            });
            if (!res.ok) {
                let message = "Failed to open session";
                try {
                    const err = await res.json();
                    if (err && err.error) message = err.error;
                } catch (_) {
                    message = await res.text();
                }
                throw new Error(message);
            }
            const data = await res.json();
            info = {
                id: data.id,
                name: data.name || name || data.id,
                transcriptPath: data.transcriptPath,
                contextMdPath: data.contextMdPath || null
            };
        } catch (e) {
            setStatus("session error", "offline");
            alert(e.message || "Failed to open session");
            throw e;
        }

        session = new Session(info.id, info.name, info.transcriptPath, info.contextMdPath);
        this._addSession(session);
        this.switchTo(info.id);
        return session;
    },

    /** Attach to sessions that already exist on the backend, then choose one. */
    async restoreExisting() {
        try {
            const res = await fetch("/api/sessions");
            if (!res.ok) throw new Error(await res.text());
            const sessions = await res.json();
            if (Array.isArray(sessions) && sessions.length > 0) {
                sessions.forEach(info => {
                    if (this.sessions.has(info.id)) return;
                    const session = new Session(
                        info.id,
                        info.name || info.id,
                        info.transcriptPath,
                        info.contextMdPath || null
                    );
                    this._addSession(session);
                });
                this.switchTo(sessions[0].id);
                return true;
            }
        } catch (_) {
            // Fall through and create the default session below.
        }
        return false;
    },

    _addSession(session) {
        // Create DOM container for this session's messages
        const el = document.createElement("div");
        el.className = "session-msgs";
        el.id = "session-msgs-" + session.id;
        el.addEventListener("scroll", () => {
            if (session === this.current && session.isNearBottom()) {
                jumpBottomEl.classList.add("hidden");
            }
        });
        messagesContainer.appendChild(el);
        session.el = el;

        this.sessions.set(session.id, session);
        if (!this.tabOrder.includes(session.id)) {
            this.tabOrder.push(session.id);
        }
        this._renderTabs();
    },

    switchTo(id) {
        const session = this.sessions.get(id);
        if (!session || session === this.current) return;

        // Save current session state
        if (this.current) {
            this.current.inputDraft = inputEl.value;
            this.current.scrollTop = this.current.el ? this.current.el.scrollTop : 0;
            this.current.el.classList.add("hidden");
        }

        // Activate new session
        this.current = session;
        session.el.classList.remove("hidden");
        session.el.scrollTop = session.scrollTop;
        inputEl.value = session.inputDraft;
        autoResize();

        // Update status based on connection
        setStatus(session.connected ? "online" : "offline",
                  session.connected ? "online" : "offline");

        // Update jump-to-bottom button visibility
        if (session.isNearBottom()) {
            jumpBottomEl.classList.add("hidden");
        } else {
            jumpBottomEl.classList.remove("hidden");
        }

        // Ensure connected
        if (!session.connected) {
            session.connect();
        }

        // Highlight active tab
        this._renderTabs();
    },

    close(id) {
        const session = this.sessions.get(id);
        if (!session) return;

        // Don't close the last session
        if (this.sessions.size <= 1) return;

        session.disconnect();
        if (session.el) session.el.remove();
        this.sessions.delete(id);
        this.tabOrder = this.tabOrder.filter(sid => sid !== id);

        // Tell backend to clean up
        fetch("/api/sessions/" + encodeURIComponent(id), { method: "DELETE" }).catch(() => {});

        // Switch to another session if we closed the current one
        if (this.current === session) {
            const nextId = this.tabOrder[this.tabOrder.length - 1];
            if (nextId) this.switchTo(nextId);
        }
        this._renderTabs();
    },

    _renderTabs() {
        tabListEl.innerHTML = "";
        this.tabOrder.forEach(sid => {
            const session = this.sessions.get(sid);
            if (!session) return;
            const tab = document.createElement("div");
            tab.className = "tab" + (session === this.current ? " active" : "");
            tab.title = (session.hasContext() ? "Context: " + session.contextMdPath + "\nTranscript: " : "Transcript: ")
                + session.transcriptPath;
            tab.onclick = () => this.switchTo(sid);

            // Context indicator badge
            if (session.hasContext()) {
                const ctxBadge = document.createElement("span");
                ctxBadge.className = "tab-ctx-badge";
                ctxBadge.textContent = "📄";
                ctxBadge.title = "Has context MD: " + session.contextMdPath;
                tab.appendChild(ctxBadge);
            }

            const label = document.createElement("span");
            label.className = "tab-label";
            label.textContent = session.name;
            tab.appendChild(label);

            const status = document.createElement("span");
            status.className = "tab-status " + (session.connected ? "online" : "offline");
            tab.appendChild(status);

            // Close button (don't close the last tab)
            const closeBtn = document.createElement("button");
            closeBtn.className = "tab-close";
            closeBtn.innerHTML = "×";
            closeBtn.title = "Close session";
            closeBtn.onclick = (e) => { e.stopPropagation(); this.close(sid); };
            tab.appendChild(closeBtn);

            tabListEl.appendChild(tab);
        });
    }
};

// ── Access pairing ───────────────────────────────────────────────────────

async function ensureAccess() {
    try {
        const res = await fetch("/api/access/status");
        const data = await res.json();
        if (data.authenticated) {
            accessModal.classList.add("hidden");
            return true;
        }
        showAccessModal(data.message || "This device is not trusted.");
        return false;
    } catch (e) {
        showAccessModal("Unable to check access status.");
        return false;
    }
}

function showAccessModal(message) {
    accessError.textContent = message || "";
    accessError.classList.toggle("hidden", !message);
    accessModal.classList.remove("hidden");
    setStatus("locked", "offline");
    setTimeout(() => {
        if (!accessDeviceName.value) accessDeviceName.focus();
        else accessCode.focus();
    }, 0);
}

async function claimAccess() {
    const code = accessCode.value.trim();
    const name = accessDeviceName.value.trim();
    if (!code) {
        showAccessModal("Enter the pairing code from /device pair.");
        return;
    }
    accessClaimBtn.disabled = true;
    try {
        const res = await fetch("/api/access/claim", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ code, name })
        });
        if (!res.ok) {
            let message = "Pairing failed";
            try {
                const err = await res.json();
                message = err.error || message;
            } catch (_) {
                message = await res.text();
            }
            throw new Error(message);
        }
        accessModal.classList.add("hidden");
        await startApp();
    } catch (e) {
        showAccessModal(e.message || "Pairing failed");
    } finally {
        accessClaimBtn.disabled = false;
    }
}

accessClaimBtn.addEventListener("click", claimAccess);
accessRetryBtn.addEventListener("click", ensureAccess);
accessCode.addEventListener("keydown", (e) => {
    if (e.key === "Enter") claimAccess();
});

// Update tab connection indicator
function updateTabState(session) {
    const tabs = tabListEl.querySelectorAll(".tab");
    const idx = sessionMgr.tabOrder.indexOf(session.id);
    if (idx >= 0 && idx < tabs.length) {
        const status = tabs[idx].querySelector(".tab-status");
        if (status) {
            status.className = "tab-status " + (session.connected ? "online" : "offline");
        }
    }
}

// ── Jump-to-bottom (acts on current session) ────────────────────────────

jumpBottomEl.addEventListener("click", () => {
    const s = sessionMgr.current;
    if (s && s.el) {
        s.el.scrollTop = s.el.scrollHeight;
        jumpBottomEl.classList.add("hidden");
    }
});

// ── New session modal ───────────────────────────────────────────────────

newTabBtn.addEventListener("click", () => {
    sessionModal.classList.remove("hidden");
    sessionMdFile.value = "";
    sessionMdFile.focus();
});

sessionCancelBtn.addEventListener("click", () => {
    sessionModal.classList.add("hidden");
});

sessionOpenBtn.addEventListener("click", async () => {
    const path = sessionMdFile.value.trim();
    sessionModal.classList.add("hidden");

    let sessionId, sessionName, mdFile;
    if (path) {
        // Derive id/name from file path
        const parts = path.replace(/\\/g, "/").split("/");
        const fileName = parts[parts.length - 1];
        sessionId = fileName.endsWith(".md") ? fileName.slice(0, -3) : fileName;
        // Sanitize id: only allow safe chars
        sessionId = sessionId.replace(/[^a-zA-Z0-9._-]/g, "-").substring(0, 64);
        if (!sessionId || !/[a-zA-Z0-9]/.test(sessionId.charAt(0))) sessionId = "s-" + sessionId;
        sessionName = fileName.endsWith(".md") ? fileName.slice(0, -3) : fileName;
        mdFile = path;
    } else {
        // New empty session with auto-generated name
        sessionId = "session-" + Date.now();
        sessionName = "Chat " + (sessionMgr.sessions.size + 1);
        mdFile = null;
    }
    await sessionMgr.open(sessionId, sessionName, mdFile);
});

// Close modal on overlay click
sessionModal.querySelector(".modal-overlay").addEventListener("click", () => {
    sessionModal.classList.add("hidden");
});

// Open modal with Enter key
sessionMdFile.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
        e.preventDefault();
        sessionOpenBtn.click();
    }
    if (e.key === "Escape") {
        sessionModal.classList.add("hidden");
    }
});

// ── WebSocket status ─────────────────────────────────────────────────────

function setStatus(text, cls) {
    statusEl.textContent = text;
    statusEl.className = "status " + cls;
}

// ── Markdown rendering (shared) ──────────────────────────────────────────

function renderMarkdown(md) {
    if (typeof marked === "undefined") return escapeHtml(md);
    const raw = marked.parse(md, { breaks: true });
    if (typeof DOMPurify !== "undefined") {
        return DOMPurify.sanitize(raw, { ALLOWED_TAGS: [
            "p", "br", "strong", "em", "b", "i", "u", "s", "del", "ins", "sub", "sup",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "dl", "dt", "dd",
            "a", "img", "blockquote", "pre", "code", "hr",
            "table", "thead", "tbody", "tr", "th", "td",
            "div", "span", "input", "label",
            "details", "summary"
        ], ALLOWED_ATTR: [
            "href", "src", "alt", "title", "target", "rel",
            "class", "id", "type", "checked", "disabled"
        ] });
    }
    return escapeHtml(md);
}

// ── Send ─────────────────────────────────────────────────────────────────

function send() {
    const content = inputEl.value.trim();
    const session = sessionMgr.current;
    if (!content || !session || !session.ws || session.ws.readyState !== WebSocket.OPEN) return;

    session.ws.send(JSON.stringify({ content }));
    session.addToHistory(content);
    session.inputHistoryIndex = -1;
    session.inputHistoryDraft = "";

    inputEl.value = "";
    autoResize();
    hideAutocomplete();
}

function autoResize() {
    inputEl.style.height = "auto";
    inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + "px";
}

// ── Agent roster (shared) ────────────────────────────────────────────────

async function loadAgents() {
    try {
        const res = await fetch("/api/agents");
        applyRoster(await res.json());
    } catch (e) { agents = []; }
}

function applyRoster(data) {
    if (!data) return;
    agents = data.agents || [];
    owner = !!data.owner;
    renderMembers();
}

function renderMembers() {
    membersEl.innerHTML = "";
    agents.forEach(a => {
        const card = document.createElement("div");
        card.className = "member" + (a.ready ? "" : " not-ready");

        const head = document.createElement("div");
        head.className = "member-head";
        head.innerHTML = "<span class='name'>" + escapeHtml(a.name) + "</span>"
            + "<span class='handle'>@" + a.id + "</span>";
        card.appendChild(head);

        const tags = document.createElement("div");
        tags.className = "member-tags";
        tags.appendChild(tag(a.ready ? "ready" : "not configured", a.ready ? "ok" : "warn"));
        if (a.freeModeSupported) {
            const freeTag = tag(a.freeMode ? "free" : "paid", a.freeMode ? "free" : "paid");
            freeTag.style.cursor = "pointer";
            freeTag.title = a.freeMode ? "Click to switch to paid mode" : "Click to switch to free mode";
            freeTag.onclick = () => {
                const s = sessionMgr.current;
                if (s && s.ws && s.ws.readyState === WebSocket.OPEN) {
                    s.ws.send(JSON.stringify({ content: "/free " + a.id + " " + (a.freeMode ? "off" : "on") }));
                }
            };
            tags.appendChild(freeTag);
        }
        tags.appendChild(tag(a.provider, "plain"));
        card.appendChild(tags);

        if (a.model) {
            const modelLine = document.createElement("div");
            modelLine.className = "member-model";
            modelLine.textContent = a.model;
            card.appendChild(modelLine);
        }

        membersEl.appendChild(card);
    });
}

function tag(text, cls) {
    const el = document.createElement("span");
    el.className = "tag " + cls;
    el.textContent = text;
    return el;
}

function escapeHtml(s) {
    return (s || "").replace(/[&<>"']/g, c =>
        ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// ── Download markdown ─────────────────────────────────────────────────────

function downloadMarkdown(md, senderName) {
    const blob = new Blob([md], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = (senderName || "agent").replace(/[^a-zA-Z0-9_\u4e00-\u9fff]/g, "-") + "-response.md";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

// ── Autocomplete (shared input, current session) ─────────────────────────

function currentMentionQuery() {
    const pos = inputEl.selectionStart;
    const upto = inputEl.value.slice(0, pos);
    const at = upto.lastIndexOf("@");
    if (at < 0) return null;
    const between = upto.slice(at + 1);
    if (/\s/.test(between)) return null;
    return { at, query: between.toLowerCase() };
}

function updateAutocomplete() {
    const ctx = currentMentionQuery();
    if (!ctx) return hideAutocomplete();
    const options = [{ id: "all", name: "Everyone", sub: "ask all agents" }]
        .concat(agents.map(a => ({ id: a.id, name: a.name, sub: a.ready ? "ready" : "not configured" })));
    acItems = options.filter(o => o.id.toLowerCase().startsWith(ctx.query));
    if (acItems.length === 0) return hideAutocomplete();
    acIndex = 0;
    acEl.innerHTML = "";
    acItems.forEach((o, i) => {
        const item = document.createElement("div");
        item.className = "item" + (i === acIndex ? " active" : "");
        item.innerHTML = "@" + o.id + "<span class='sub'>" + o.name + " · " + o.sub + "</span>";
        item.onmousedown = (e) => { e.preventDefault(); applyMention(o.id); };
        acEl.appendChild(item);
    });
    acEl.classList.remove("hidden");
}

function applyMention(id) {
    const ctx = currentMentionQuery();
    if (!ctx) return;
    const pos = inputEl.selectionStart;
    const before = inputEl.value.slice(0, ctx.at);
    const after = inputEl.value.slice(pos);
    inputEl.value = before + "@" + id + " " + after;
    hideAutocomplete();
    inputEl.focus();
}

function hideAutocomplete() {
    acEl.classList.add("hidden");
    acItems = [];
}

function moveActive(delta) {
    if (acItems.length === 0) return;
    acIndex = (acIndex + delta + acItems.length) % acItems.length;
    [...acEl.children].forEach((c, i) => c.classList.toggle("active", i === acIndex));
}

// ── Keyboard ─────────────────────────────────────────────────────────────

inputEl.addEventListener("input", () => { autoResize(); updateAutocomplete(); });
inputEl.addEventListener("keydown", (e) => {
    const acOpen = !acEl.classList.contains("hidden");

    if (acOpen) {
        if (e.key === "ArrowDown" || e.key === "ArrowUp") {
            e.preventDefault();
            moveActive(e.key === "ArrowDown" ? 1 : -1);
            return;
        }
        if (e.key === "Enter" || e.key === "Tab") {
            e.preventDefault();
            applyMention(acItems[acIndex].id);
            return;
        }
        if (e.key === "Escape") {
            e.preventDefault();
            hideAutocomplete();
            return;
        }
    }

    // Send: Ctrl+Enter (Cmd+Enter on Mac)
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        send();
        return;
    }

    // Input history: Up/Down arrows (per-session)
    if (!acOpen && e.key === "ArrowUp") {
        e.preventDefault();
        if (sessionMgr.current) sessionMgr.current.navigateHistory(-1);
        return;
    }
    if (!acOpen && e.key === "ArrowDown") {
        e.preventDefault();
        if (sessionMgr.current) sessionMgr.current.navigateHistory(1);
        return;
    }
});

sendEl.addEventListener("click", send);

// ── Init ─────────────────────────────────────────────────────────────────

async function startApp() {
    await loadAgents();
    const restored = await sessionMgr.restoreExisting();
    if (!restored) {
        await sessionMgr.open("main", "Main Chat", null);
    }
}

ensureAccess().then(async ok => {
    if (ok) {
        await startApp();
    }
});
