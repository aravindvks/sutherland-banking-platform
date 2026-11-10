import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import Keycloak from 'keycloak-js';
import App from './App';
import { auth } from './shared/api';
// Fonts are bundled with the app (no third-party font requests from a banking page).
import '@fontsource-variable/instrument-sans';
import '@fontsource/ibm-plex-mono/400.css';
import './styles.css';

const keycloakUrl = import.meta.env.VITE_KEYCLOAK_URL as string | undefined;

async function start() {
  // Login through Identity Domains (Keycloak locally). Without VITE_KEYCLOAK_URL the app runs with
  // development headers, matching a BFF started with pbp.auth.enabled=false.
  if (keycloakUrl) {
    const kc = new Keycloak({ url: keycloakUrl, realm: 'pbp', clientId: 'pbp-web' });
    await kc.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false });
    auth.enabled = true;
    auth.token = async () => {
      try {
        await kc.updateToken(30);
      } catch {
        await kc.login();
      }
      return kc.token;
    };
    auth.username = (kc.tokenParsed?.preferred_username as string) ?? '';
    auth.roles = (kc.tokenParsed?.realm_access?.roles as string[]) ?? [];
    auth.logout = () => kc.logout({ redirectUri: window.location.origin });
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

start();
