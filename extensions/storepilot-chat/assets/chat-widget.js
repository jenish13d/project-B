(function () {
  "use strict";

  var root = document.getElementById("storepilot-chat");
  if (!root || root.dataset.ready) return;
  root.dataset.ready = "1";

  var STORAGE_KEY = "storepilot-chat";
  var endpoint = root.dataset.endpoint;
  var greeting = root.dataset.greeting;

  // Conversation id and transcript survive page navigation within the visit.
  function load() {
    try {
      return JSON.parse(sessionStorage.getItem(STORAGE_KEY)) || {};
    } catch (e) {
      return {};
    }
  }
  function save() {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (e) {
      /* storage unavailable: chat still works for this page */
    }
  }
  var state = load();
  state.messages = state.messages || [];

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
  }

  var launcher = el("button", "storepilot-launcher", "💬");
  launcher.type = "button";
  launcher.setAttribute("aria-label", "Open chat");

  var panel = el("div", "storepilot-panel");
  panel.hidden = true;
  panel.setAttribute("role", "dialog");
  panel.setAttribute("aria-label", root.dataset.title || "Chat");

  var header = el("div", "storepilot-header", root.dataset.title || "Chat");
  var close = el("button", "storepilot-close", "×");
  close.type = "button";
  close.setAttribute("aria-label", "Close chat");
  header.appendChild(close);

  var list = el("div", "storepilot-messages");
  list.setAttribute("aria-live", "polite");

  var form = el("form", "storepilot-form");
  var input = el("input", "storepilot-input");
  input.type = "text";
  input.maxLength = 1000;
  input.placeholder = "Type your question…";
  input.setAttribute("aria-label", "Your message");
  var send = el("button", "storepilot-send", "Send");
  send.type = "submit";
  form.appendChild(input);
  form.appendChild(send);

  panel.appendChild(header);
  panel.appendChild(list);
  panel.appendChild(form);
  root.appendChild(panel);
  root.appendChild(launcher);

  function addBubble(role, text, extraClass) {
    var bubble = el("div", "storepilot-msg storepilot-msg--" + role + (extraClass ? " " + extraClass : ""), text);
    list.appendChild(bubble);
    list.scrollTop = list.scrollHeight;
    return bubble;
  }

  if (greeting) addBubble("assistant", greeting);
  state.messages.forEach(function (m) {
    addBubble(m.role, m.content);
  });

  function toggle(open) {
    panel.hidden = !open;
    launcher.setAttribute("aria-expanded", String(open));
    if (open) input.focus();
  }
  launcher.addEventListener("click", function () {
    toggle(panel.hidden);
  });
  close.addEventListener("click", function () {
    toggle(false);
  });

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    var text = input.value.trim();
    if (!text || send.disabled) return;

    input.value = "";
    send.disabled = true;
    addBubble("customer", text);
    state.messages.push({ role: "customer", content: text });
    save();
    var typing = addBubble("assistant", "Typing…", "storepilot-msg--typing");

    fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ conversationId: state.conversationId || null, message: text }),
    })
      .then(function (res) {
        return res.json().catch(function () {
          return {};
        });
      })
      .then(function (data) {
        var reply = data.reply || "Sorry, something went wrong. Please try again.";
        if (data.conversationId) state.conversationId = data.conversationId;
        typing.remove();
        addBubble("assistant", reply);
        state.messages.push({ role: "assistant", content: reply });
        save();
      })
      .catch(function () {
        typing.remove();
        addBubble("assistant", "Sorry, I couldn't connect. Please check your connection and try again.");
      })
      .finally(function () {
        send.disabled = false;
        input.focus();
      });
  });
})();
