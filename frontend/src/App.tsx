import { useEffect, useState } from 'react';

type ConnectionStatus = 'checking' | 'connected' | 'disconnected';

const statusMessages: Record<ConnectionStatus, string> = {
  checking: 'Checking backend connection…',
  connected: 'Backend connected',
  disconnected: 'Backend disconnected. Check that the backend is running.',
};

export default function App() {
  const [status, setStatus] = useState<ConnectionStatus>('checking');

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 5000);

    async function checkHealth() {
      try {
        const baseUrl = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');
        const response = await fetch(`${baseUrl}/api/health`, {
          signal: controller.signal,
        });
        if (!response.ok) throw new Error('Health request failed');
        const data: unknown = await response.json();
        if (typeof data !== 'object' || data === null || !('status' in data) || data.status !== 'ok') {
          throw new Error('Unexpected health response');
        }
        if (active) setStatus('connected');
      } catch {
        if (active) setStatus('disconnected');
      } finally {
        window.clearTimeout(timeout);
      }
    }

    void checkHealth();
    return () => {
      active = false;
      window.clearTimeout(timeout);
      controller.abort();
    };
  }, []);

  return (
    <main>
      <h1>GameVault</h1>
      <p>Your video game collection starts here.</p>
      <p role="status" className={`connection ${status}`}>{statusMessages[status]}</p>
    </main>
  );
}
