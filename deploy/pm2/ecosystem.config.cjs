'use strict';

const path = require('node:path');

function absoluteFromEnv(name, fallback) {
  const value = process.env[name] || fallback;
  if (!path.isAbsolute(value)) {
    throw new Error(`${name} must be an absolute path: ${value}`);
  }
  return path.normalize(value);
}

const releaseRoot = absoluteFromEnv('YULIN_ROOT', '/opt/yulin-youxian');
const dataRoot = absoluteFromEnv('YULIN_DATA_ROOT', '/var/lib/yulin-youxian');
const logRoot = absoluteFromEnv('YULIN_LOG_ROOT', '/var/log/yulin-youxian');
const serverPort = process.env.SERVER_PORT || '8080';

if (!/^[0-9]{2,5}$/.test(serverPort)) {
  throw new Error(`SERVER_PORT is invalid: ${serverPort}`);
}

module.exports = {
  apps: [
    {
      name: 'yulin-youxian-server',
      script: '/usr/bin/java',
      args: [
        '-Dfile.encoding=UTF-8',
        '-Duser.timezone=Asia/Shanghai',
        '-jar',
        path.join(releaseRoot, 'current', 'server', 'fresh-delivery-server.jar'),
        '--server.address=127.0.0.1',
        `--server.port=${serverPort}`
      ],
      interpreter: 'none',
      cwd: dataRoot,
      instances: 1,
      exec_mode: 'fork',
      autorestart: true,
      min_uptime: '15s',
      max_restarts: 10,
      restart_delay: 5000,
      kill_timeout: 20000,
      listen_timeout: 30000,
      time: true,
      merge_logs: true,
      out_file: path.join(logRoot, 'server-out.log'),
      error_file: path.join(logRoot, 'server-error.log'),
      // RIDER_APP_PUBLISH_TOKEN / RIDER_EXPECTED_CERT_SHA256 只注入运行中的 PM2
      // 进程并写入 dump，不要写进这份入库配置。
      env: {
        TZ: 'Asia/Shanghai',
        SERVER_ADDRESS: '127.0.0.1',
        SERVER_PORT: serverPort,
        PERSISTENCE_MODE: 'mysql',
        AUTH_PROFILE_STORAGE_PATH: path.join(dataRoot, 'data', 'user-profiles.json'),
        STOREFRONT_STORAGE_PATH: path.join(dataRoot, 'data', 'storefront-state.json'),
        PRINTING_STORAGE_PATH: path.join(dataRoot, 'data', 'printing-state.json'),
        BACKUP_DIRECTORY: path.join(dataRoot, 'data', 'backups'),
        BACKUP_DATA_DIRECTORY: path.join(dataRoot, 'data'),
        DELIVERY_UPLOAD_PATH: path.join(dataRoot, 'data', 'uploads', 'delivery')
      }
    }
  ]
};
