// Client-side-only login gate. This is deliberately not real security - it's
// a presentation-layer gate for a demo dashboard. Writes to Heimdall itself
// are still protected by the gateway's real X-API-Key check.
const HeimdallAuth = {
  SESSION_KEY: "heimdall_auth",

  isLoggedIn() {
    return sessionStorage.getItem(this.SESSION_KEY) === "ok";
  },

  attempt(username, password) {
    if (username === HEIMDALL_CONFIG.LOGIN_USERNAME && password === HEIMDALL_CONFIG.LOGIN_PASSWORD) {
      sessionStorage.setItem(this.SESSION_KEY, "ok");
      return true;
    }
    return false;
  },

  logout() {
    sessionStorage.removeItem(this.SESSION_KEY);
    window.location.href = "login.html";
  },

  // Call at the top of every protected page.
  requireLogin() {
    if (!this.isLoggedIn()) {
      window.location.href = "login.html";
    }
  },

  wireLogoutButton() {
    const btn = document.getElementById("logoutBtn");
    if (btn) {
      btn.addEventListener("click", () => this.logout());
    }
  },
};

HeimdallAuth.requireLogin();
