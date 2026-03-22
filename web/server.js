const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execSync, exec } = require('child_process');
const url = require('url');
const os = require('os');

const PORT = 3456;
const FORGE_BIN = path.join(__dirname, '..', 'build', 'install', 'forge', 'bin', 'forge');
const FORGE_WORKSPACES_DIR = path.join(os.homedir(), '.forge', 'workspaces');

// Mutable — changed via /api/set-path
let REPO_PATH = '/Users/nikolay/Desktop/NEW_IDE/intellij-community-master';
let DB_PATH = '/Users/nikolay/.forge/workspaces/4ca7e765e159e0d1277db7b3dc026425de34b1ce817021bac81a2d6dc2667544/workspace.db';

function sha256Hex(input) {
  return crypto.createHash('sha256').update(input, 'utf8').digest('hex');
}

function computeDbPath(repoPath) {
  const normalized = path.resolve(repoPath);
  const hash = sha256Hex(normalized);
  return path.join(FORGE_WORKSPACES_DIR, hash, 'workspace.db');
}

function getWorkspaces() {
  try {
    const entries = fs.readdirSync(FORGE_WORKSPACES_DIR, { withFileTypes: true });
    const workspaces = [];
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const metaPath = path.join(FORGE_WORKSPACES_DIR, entry.name, 'meta.json');
      const dbPath = path.join(FORGE_WORKSPACES_DIR, entry.name, 'workspace.db');
      try {
        const meta = JSON.parse(fs.readFileSync(metaPath, 'utf8'));
        workspaces.push({
          repoPath: meta.repoPath,
          hash: entry.name,
          createdAt: meta.createdAt,
          hasDb: fs.existsSync(dbPath),
          isCurrent: meta.repoPath === REPO_PATH
        });
      } catch {}
    }
    return workspaces.sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''));
  } catch {
    return [];
  }
}

function browseDirs(dirPath) {
  const resolved = path.resolve(dirPath || os.homedir());
  try {
    const entries = fs.readdirSync(resolved, { withFileTypes: true });
    const dirs = entries
      .filter(e => e.isDirectory() && !e.name.startsWith('.'))
      .map(e => ({ name: e.name, path: path.join(resolved, e.name) }))
      .sort((a, b) => a.name.localeCompare(b.name));
    return {
      current: resolved,
      parent: path.dirname(resolved),
      dirs,
      isGitRepo: fs.existsSync(path.join(resolved, '.git'))
    };
  } catch (e) {
    return { current: resolved, parent: path.dirname(resolved), dirs: [], error: e.message };
  }
}

function getStats() {
  try {
    const sqlite = (sql) => {
      try {
        return execSync(`sqlite3 "${DB_PATH}" "${sql}"`, { timeout: 10000 }).toString().trim();
      } catch { return '0'; }
    };

    const files = parseInt(sqlite("SELECT COUNT(*) FROM files;")) || 0;
    const modules = parseInt(sqlite("SELECT COUNT(*) FROM modules;")) || 0;
    const moduleTypes = sqlite("SELECT COUNT(DISTINCT module_type) FROM modules;") + ' types';
    const chunks = parseInt(sqlite("SELECT COUNT(*) FROM chunks;")) || 0;
    const embeddings = parseInt(sqlite("SELECT COUNT(*) FROM chunks WHERE embedding IS NOT NULL AND length(embedding) > 0;")) || 0;
    const embeddingPct = chunks > 0 ? Math.round(embeddings / chunks * 100) : 0;

    let ollamaModels = '?';
    try {
      const resp = execSync('curl -s http://127.0.0.1:11434/api/tags', { timeout: 5000 }).toString();
      const data = JSON.parse(resp);
      ollamaModels = (data.models || []).length;
    } catch {}

    return { files, modules, moduleTypes, chunks, embeddings, embeddingPct, ollamaModels, repoPath: REPO_PATH };
  } catch (e) {
    return { files: 0, modules: 0, moduleTypes: '?', chunks: 0, embeddings: 0, embeddingPct: 0, ollamaModels: '?', repoPath: REPO_PATH };
  }
}

function stripAnsi(str) {
  return str.replace(/\x1B\[[0-9;]*[a-zA-Z]/g, '').replace(/\[[\d;]*m/g, '');
}

function parseForgeOutput(output) {
  output = stripAnsi(output);
  const trace = [];
  const traceRegex = /│\s*([A-Z_]+)\s*│\s*(.*?)\s*│\s*(\d+)m/g;
  let match;
  while ((match = traceRegex.exec(output)) !== null) {
    trace.push({ stage: match[1], detail: match[2].trim(), durationMs: parseInt(match[3]) });
  }

  let response = '';
  const taskLineMatch = output.match(/Task:\s+.*\|.*Model:.*\n/);
  if (taskLineMatch) {
    const afterTask = output.substring(taskLineMatch.index + taskLineMatch[0].length);
    const firstSep = afterTask.match(/─{10,}\n?/);
    if (firstSep) {
      const content = afterTask.substring(firstSep.index + firstSep[0].length);
      response = content.replace(/\n─{10,}\s*(\[OK\].*)?$/s, '').trim();
    }
  }
  if (!response) {
    const allSeps = [...output.matchAll(/─{20,}/g)];
    if (allSeps.length >= 2) {
      const lastSepEnd = allSeps[allSeps.length - 2].index + allSeps[allSeps.length - 2][0].length;
      response = output.substring(lastSepEnd).replace(/─{10,}\s*(\[OK\].*)?$/s, '').trim();
    }
  }

  return { trace, response, raw: output };
}

function runForge(args, timeout = 600000) {
  return new Promise((resolve, reject) => {
    const cmd = `"${FORGE_BIN}" ${args}`;
    exec(cmd, { timeout, maxBuffer: 10 * 1024 * 1024 }, (error, stdout, stderr) => {
      if (error && !stdout) {
        reject(new Error(stderr || error.message));
      } else {
        resolve(stdout + (stderr || ''));
      }
    });
  });
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', chunk => { data += chunk; });
    req.on('end', () => {
      try { resolve(JSON.parse(data)); }
      catch { reject(new Error('Invalid JSON')); }
    });
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const parsed = url.parse(req.url, true);

  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  if (parsed.pathname === '/' || parsed.pathname === '/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(fs.readFileSync(path.join(__dirname, 'index.html')));
    return;
  }

  if (parsed.pathname === '/api/stats') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(getStats()));
    return;
  }

  if (parsed.pathname === '/api/current-path') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ repoPath: REPO_PATH, dbPath: DB_PATH, hasDb: fs.existsSync(DB_PATH) }));
    return;
  }

  if (parsed.pathname === '/api/workspaces') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(getWorkspaces()));
    return;
  }

  if (parsed.pathname === '/api/browse') {
    const dir = parsed.query.dir || os.homedir();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(browseDirs(dir)));
    return;
  }

  if (parsed.pathname === '/api/set-path' && req.method === 'POST') {
    try {
      const body = await readBody(req);
      const newPath = body.path;
      if (!newPath) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Missing "path" field' }));
        return;
      }
      const resolved = path.resolve(newPath);
      if (!fs.existsSync(resolved)) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: `Path does not exist: ${resolved}` }));
        return;
      }
      REPO_PATH = resolved;
      DB_PATH = computeDbPath(resolved);
      const hasDb = fs.existsSync(DB_PATH);
      console.log(`  Path changed to: ${REPO_PATH}`);
      console.log(`  DB: ${DB_PATH} (${hasDb ? 'exists' : 'not found — run analyze first'})`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ repoPath: REPO_PATH, dbPath: DB_PATH, hasDb }));
    } catch (e) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: e.message }));
    }
    return;
  }

  if (parsed.pathname === '/api/status') {
    try {
      const output = stripAnsi(await runForge('status', 30000));
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ output }));
    } catch (e) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: e.message }));
    }
    return;
  }

  if (parsed.pathname === '/api/analyze') {
    try {
      const output = await runForge(`analyze "${REPO_PATH}"`, 600000);
      const result = parseForgeOutput(output);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(result));
    } catch (e) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: e.message }));
    }
    return;
  }

  if (parsed.pathname === '/api/focus') {
    const module = parsed.query.module || 'platform/lang-api';
    const query = parsed.query.query || 'What are the main abstractions?';
    try {
      const output = await runForge(`focus "${module}" "${REPO_PATH}" "${query}"`, 600000);
      const result = parseForgeOutput(output);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(result));
    } catch (e) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: e.message }));
    }
    return;
  }

  res.writeHead(404);
  res.end('Not found');
});

server.listen(PORT, () => {
  console.log(`\n  FORGE Web Dashboard running at http://localhost:${PORT}`);
  console.log(`  Repository: ${REPO_PATH}`);
  console.log(`  Database:   ${DB_PATH}\n`);
});
