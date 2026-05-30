#!/usr/bin/env node
'use strict';

const { Command } = require('commander');
const { spawnSync, spawn } = require('child_process');
const { existsSync } = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');

const AGENT_HOME = process.env.AGENT_HOME ?? path.join(os.homedir(), '.hugin');
const SERVER_JAR = path.join(AGENT_HOME, 'bin', 'mcp-integration.jar');
const TERMINAL_JAR = path.join(AGENT_HOME, 'bin', 'agent-terminal.jar');
const CONFIG_YML = path.join(AGENT_HOME, 'config', 'application.yml');

const installed = () => existsSync(SERVER_JAR);
const hasSystemd = () => spawnSync('systemctl', ['--version'], { stdio: 'ignore' }).status === 0;
const hasHuginLauncher = () => spawnSync('which', ['hugin'], { stdio: 'ignore' }).status === 0;

function die(msg) {
  process.stderr.write(`\x1b[31m[hugin]\x1b[0m ${msg}\n`);
  process.exit(1);
}

function info(msg) {
  process.stdout.write(`\x1b[34m[hugin]\x1b[0m ${msg}\n`);
}

function success(msg) {
  process.stdout.write(`\x1b[32m[hugin]\x1b[0m ${msg}\n`);
}

// Run a command, inheriting stdio, and exit with its status code.
function sh(cmd, args = [], opts = {}) {
  const result = spawnSync(cmd, args, { stdio: 'inherit', ...opts });
  process.exit(result.status ?? 1);
}

// Spawn a long-running process that replaces this process's stdio.
function exec(cmd, args = []) {
  const child = spawn(cmd, args, { stdio: 'inherit' });
  child.on('exit', code => process.exit(code ?? 0));
}

function requireInstalled() {
  if (!installed()) {
    die(
      `No installed jars found in ${AGENT_HOME}.\n` +
      '  Run "hugin onboard" to build and install, or set AGENT_HOME.'
    );
  }
}

function waitForHealth(timeoutSecs = 30) {
  return new Promise(resolve => {
    const deadline = Date.now() + timeoutSecs * 1000;
    function check() {
      const req = http.get('http://localhost:8080/actuator/health', res => {
        if (res.statusCode === 200) return resolve(true);
        retry();
      });
      req.on('error', retry);
      function retry() {
        if (Date.now() >= deadline) return resolve(false);
        setTimeout(check, 1000);
      }
    }
    check();
  });
}

const program = new Command();

program
  .name('hugin')
  .description('Hugin AI personal assistant CLI')
  .version('0.1.0');

// ── onboard ───────────────────────────────────────────────────────────────────

program
  .command('onboard')
  .description('Run the interactive setup wizard (build, install, configure)')
  .action(() => {
    const installScript = path.join(__dirname, '..', '..', 'install.sh');
    if (!existsSync(installScript)) {
      die(
        `install.sh not found at ${installScript}.\n` +
        '  Make sure you installed from the repo root: npm install -g .'
      );
    }
    sh('bash', [installScript]);
  });

// ── server subcommands ────────────────────────────────────────────────────────

const server = program.command('server').description('Manage the agent server');

server
  .command('start')
  .description('Start the agent server via systemd')
  .action(() => {
    if (!hasSystemd()) die('systemd not found. Use "hugin server run" to start in the foreground.');
    if (!hasHuginLauncher()) die('hugin launcher not found. Run "hugin onboard" first.');
    sh('sudo', ['systemctl', 'start', 'hugin']);
  });

server
  .command('stop')
  .description('Stop the agent server via systemd')
  .action(() => {
    if (!hasSystemd()) die('systemd not found.');
    sh('sudo', ['systemctl', 'stop', 'hugin']);
  });

server
  .command('restart')
  .description('Restart the agent server via systemd')
  .action(() => {
    if (!hasSystemd()) die('systemd not found. Use "hugin server run" to start in the foreground.');
    sh('sudo', ['systemctl', 'restart', 'hugin']);
  });

server
  .command('status')
  .description('Show agent server service status')
  .action(() => {
    if (!hasSystemd()) die('systemd not found.');
    sh('systemctl', ['status', 'hugin']);
  });

server
  .command('logs')
  .description('Stream agent server logs (journalctl -f)')
  .action(() => {
    if (!hasSystemd()) die('systemd not found. Check your process manager logs manually.');
    exec('journalctl', ['-u', 'hugin', '-f']);
  });

server
  .command('run')
  .description('Run the agent server in the foreground (no systemd)')
  .action(() => {
    requireInstalled();
    info(`Starting server from ${SERVER_JAR}`);
    exec('java', [
      '-jar', SERVER_JAR,
      `--spring.config.additional-location=file:${CONFIG_YML}`,
    ]);
  });

// ── terminal ──────────────────────────────────────────────────────────────────

program
  .command('terminal')
  .description('Launch the interactive terminal client')
  .option('--server-url <url>', 'Agent server URL', process.env.AGENT_SERVER_URL ?? 'http://localhost:8080')
  .action(opts => {
    requireInstalled();
    info('Launching terminal client...');
    exec('java', [
      `-Dterminal.server-url=${opts.serverUrl}`,
      '-jar', TERMINAL_JAR,
    ]);
  });

// ── default: smart start ──────────────────────────────────────────────────────
// "hugin" with no subcommand: ensure the server is up, then open the terminal.

program.action(async () => {
  requireInstalled();

  // Check if server is already healthy; if not, try to start it.
  const alreadyUp = await waitForHealth(2);
  if (!alreadyUp) {
    if (hasHuginLauncher() && hasSystemd()) {
      info('Server not running — starting hugin service...');
      spawnSync('sudo', ['systemctl', 'start', 'hugin'], { stdio: 'inherit' });
    } else {
      info(`Server not responding. Start it with:\n  hugin server run`);
      process.exit(1);
    }

    info('Waiting for server to become healthy...');
    const healthy = await waitForHealth(30);
    if (!healthy) {
      die('Server did not become healthy within 30 s. Check: hugin server logs');
    }
    success('Server is ready on http://localhost:8080');
  }

  exec('java', [
    `-Dterminal.server-url=${process.env.AGENT_SERVER_URL ?? 'http://localhost:8080'}`,
    '-jar', TERMINAL_JAR,
  ]);
});

program.parse(process.argv);
