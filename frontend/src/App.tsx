import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';

export type GameSearchResult = {
  id: number;
  name: string;
  platforms: string[];
  coverUrl: string | null;
  firstReleaseDate: number | null;
};

type ConnectionStatus = 'checking' | 'connected' | 'disconnected';
const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const statusMessages: Record<ConnectionStatus, string> = {
  checking: 'Checking backend connection…',
  connected: 'Backend connected',
  disconnected: 'Backend disconnected. Check that the backend is running.',
};

function isGameSearchResult(value: unknown): value is GameSearchResult {
  if (typeof value !== 'object' || value === null) return false;
  const game = value as Record<string, unknown>;
  return typeof game.id === 'number'
    && typeof game.name === 'string'
    && Array.isArray(game.platforms)
    && game.platforms.every((platform: unknown) => typeof platform === 'string')
    && (game.coverUrl === null || typeof game.coverUrl === 'string')
    && (game.firstReleaseDate === null || typeof game.firstReleaseDate === 'number');
}

function GameCover({ url }: { url: string | null }) {
  const [failed, setFailed] = useState(false);
  return url && !failed ? (
    <img className="game-cover" src={url} alt="" loading="lazy" onError={() => setFailed(true)} />
  ) : (
    <div className="game-cover cover-placeholder">No cover available</div>
  );
}

export default function App() {
  const [status, setStatus] = useState<ConnectionStatus>('checking');
  const [query, setQuery] = useState('');
  const [pendingQuery, setPendingQuery] = useState<string | null>(null);
  const [completedQuery, setCompletedQuery] = useState<string | null>(null);
  const [results, setResults] = useState<GameSearchResult[]>([]);
  const [error, setError] = useState<string | null>(null);
  const activeSearch = useRef<{ query: string; controller: AbortController } | null>(null);

  useEffect(() => () => {
    activeSearch.current?.controller.abort();
    activeSearch.current = null;
  }, []);

  async function searchGames(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const term = query.trim();
    // The ref prevents duplicates even before React updates the button.
    if (!term || activeSearch.current?.query === term) return;

    activeSearch.current?.controller.abort();
    const request = { query: term, controller: new AbortController() };
    activeSearch.current = request;
    setPendingQuery(term);
    setCompletedQuery(null);
    setResults([]);
    setError(null);
    const timeout = window.setTimeout(() => request.controller.abort(), 15000);

    try {
      const params = new URLSearchParams({ q: term });
      const response = await fetch(`${apiBaseUrl}/api/games/search?${params}`, {
        signal: request.controller.signal,
      });
      if (!response.ok) {
        throw new Error(response.status === 400
          ? 'Enter a search of 1–200 characters without control characters.'
          : 'Game search is temporarily unavailable. Please try again.');
      }
      const data: unknown = await response.json();
      if (!Array.isArray(data) || !data.every(isGameSearchResult)) {
        throw new Error('The server returned an unexpected response. Please try again.');
      }
      // An older response may already have arrived when its request is aborted.
      if (activeSearch.current !== request) return;
      setResults(data.slice(0, 10));
      setCompletedQuery(term);
    } catch (cause) {
      if (activeSearch.current !== request) return;
      setError(request.controller.signal.aborted
        ? 'The search timed out. Please try again.'
        : cause instanceof TypeError
          ? 'Could not reach the backend. Check your connection and try again.'
          : cause instanceof SyntaxError
            ? 'The server returned an unexpected response. Please try again.'
            : cause instanceof Error ? cause.message : 'Search failed. Please try again.');
    } finally {
      window.clearTimeout(timeout);
      if (activeSearch.current === request) {
        activeSearch.current = null;
        setPendingQuery(null);
      }
    }
  }

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 5000);

    async function checkHealth() {
      try {
        const response = await fetch(`${apiBaseUrl}/api/health`, {
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

      <section aria-labelledby="search-heading">
        <h2 id="search-heading">Find a game</h2>
        <form className="search-form" onSubmit={searchGames}>
          <label htmlFor="game-search">Game title</label>
          <div className="search-controls">
            <input
              id="game-search"
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Try Skyrim"
              maxLength={200}
              required
            />
            <button type="submit" disabled={!query.trim() || pendingQuery === query.trim()}>
              {pendingQuery === query.trim() ? 'Searching…' : 'Search'}
            </button>
          </div>
        </form>

        <p role="status">
          {pendingQuery !== null ? `Searching for “${pendingQuery}”…`
            : completedQuery !== null
              ? results.length === 0
                ? `No games found for “${completedQuery}”. Try another title.`
                : `${results.length} ${results.length === 1 ? 'result' : 'results'} for “${completedQuery}”.`
              : !error ? 'Search for a title to discover games.' : ''}
        </p>
        {error && <p role="alert" className="search-error">{error}</p>}

        <ul className="game-grid" aria-label="Search results" aria-busy={pendingQuery !== null}>
          {results.map((game) => (
            <li className="game-card" key={game.id}>
              <GameCover key={game.coverUrl} url={game.coverUrl} />
              <div className="game-details">
                <h3>{game.name}</h3>
                <p>{game.platforms.length > 0 ? game.platforms.join(', ') : 'Platforms unavailable'}</p>
              </div>
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}
