// Central place for everything environment-specific. Edit these to point at
// a different gateway, or to change the hardcoded dashboard login.
const HEIMDALL_CONFIG = {
  GATEWAY_BASE_URL: "http://localhost:8080",
  API_KEY: "changeme", // must match heimdall.api-key on the gateway
  LOGIN_USERNAME: "admin",
  LOGIN_PASSWORD: "heimdall123",
};
