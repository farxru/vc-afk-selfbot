const { Client, Options } = require('discord.js-selfbot-v13');
const fs = require('fs');
const path = require('path');

// Ensure V8 Garbage Collection is accessible in-process
try {
  const v8 = require('v8');
  const vm = require('vm');
  v8.setFlagsFromString('--expose_gc');
  if (typeof global.gc !== 'function') {
    global.gc = vm.runInNewContext('gc');
  }
} catch (_) {}

// Load configuration
const configPath = path.join(__dirname, 'config.json');
if (!fs.existsSync(configPath)) {
  console.error('[!] config.json not found. Please create it.');
  process.exit(1);
}

let config = {};
try {
  config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
} catch (err) {
  console.error('[!] Error parsing config.json:', err.message);
  process.exit(1);
}

const MAX_ACCOUNTS = 5;

// Normalize tokens from config
let initialTokens = [];
if (Array.isArray(config.tokens)) {
  initialTokens = config.tokens.filter(
    (t) => typeof t === 'string' && t.trim().length > 0 && !t.includes('PASTE_YOUR_USER_TOKEN')
  );
}
if (
  config.token &&
  typeof config.token === 'string' &&
  config.token.trim().length > 0 &&
  !config.token.includes('PASTE_YOUR_USER_TOKEN')
) {
  const trimmed = config.token.trim();
  if (!initialTokens.includes(trimmed)) {
    initialTokens.unshift(trimmed);
  }
}
initialTokens = initialTokens.slice(0, MAX_ACCOUNTS);
config.tokens = initialTokens;

function saveConfig() {
  try {
    fs.writeFileSync(configPath, JSON.stringify(config, null, 2), 'utf8');
  } catch (err) {
    console.error('[-] Failed to save config.json:', err.message);
  }
}

// Memory cleaner and watchdog
let cleanedCount = 0;

function cleanMemory(source = 'monitor') {
  const beforeMem = process.memoryUsage();
  const beforeHeapMb = +(beforeMem.heapUsed / 1024 / 1024).toFixed(1);
  const beforeRssMb = +(beforeMem.rss / 1024 / 1024).toFixed(1);

  // 1. Sweep client internal caches across all accounts
  for (const acc of accounts.values()) {
    if (acc.client) {
      try {
        acc.client.sweepMessages?.(0);
        acc.client.channels?.cache?.forEach((ch) => {
          if (ch.messages?.cache?.size) ch.messages.cache.clear();
        });
      } catch (_) {}
    }
  }

  // 2. Trigger V8 Garbage Collection
  if (typeof global.gc === 'function') {
    try {
      global.gc();
    } catch (_) {}
  }

  const afterMem = process.memoryUsage();
  const afterHeapMb = +(afterMem.heapUsed / 1024 / 1024).toFixed(1);
  const afterRssMb = +(afterMem.rss / 1024 / 1024).toFixed(1);
  cleanedCount++;

  if (source !== 'silent') {
    console.log(
      `[RAM Cleaner] Memory cleaned (${source}): Heap ${beforeHeapMb}MB -> ${afterHeapMb}MB | RSS ${beforeRssMb}MB -> ${afterRssMb}MB`
    );
  }

  return { beforeHeapMb, afterHeapMb, beforeRssMb, afterRssMb };
}

// Active Memory Monitor: Checks every 3 seconds to keep RAM strictly under 30MB
setInterval(() => {
  const mem = process.memoryUsage();
  const heapMb = mem.heapUsed / 1024 / 1024;
  const rssMb = mem.rss / 1024 / 1024;

  if (heapMb >= 28 || rssMb >= 30) {
    cleanMemory('threshold >= 30MB');
  }
}, 3000);

/**
 * Account representation managing single Discord connection and voice state.
 */
class Account {
  constructor(id, token, defaultSettings = {}) {
    this.id = id;
    this.token = token;
    this.status = 'connecting'; // 'connecting', 'online', 'offline', 'error'
    this.errorMessage = null;

    this.stayInVc = false;
    this.targetChannelId = null;
    this.targetGuildId = null;
    this.channelName = null;
    this.isReconnecting = false;

    this.isSelfMute = Boolean(defaultSettings.selfMute);
    this.isSelfDeaf = Boolean(defaultSettings.selfDeaf ?? true);
    this.isCameraOn = false;
    this.isStreaming = false;
    this.streamingChannelId = null;
    this.streamingGuildId = null;

    // Ultra-low memory Discord client with caches set to 0
    this.client = new Client({
      checkUpdate: false,
      makeCache: Options.cacheWithLimits({
        GuildMemberManager: 0,
        UserManager: 0,
        MessageManager: 0,
        PresenceManager: 0,
        ThreadManager: 0,
        ThreadMemberManager: 0,
        StageInstanceManager: 0,
        GuildBanManager: 0,
        GuildInviteManager: 0,
        GuildScheduledEventManager: 0,
        ReactionManager: 0,
        ReactionUserManager: 0,
        BaseGuildEmojiManager: 0,
        GuildEmojiManager: 0,
        GuildStickerManager: 0,
        RoleManager: 0,
        VoiceStateManager: 10,
      }),
    });

    this.setupListeners();
    this.login();
  }

  getNickname() {
    if (!this.client?.user) return `Account ${this.id}`;
    return this.client.user.globalName || this.client.user.username;
  }

  setupListeners() {
    this.client.on('ready', () => {
      this.status = 'online';
      this.errorMessage = null;
      console.log(`[+] [${this.getNickname()}] Logged in successfully as ${this.client.user.tag}`);
      cleanMemory('account ready');
    });

    // Keep stream unpaused whenever Discord Gateway sends stream events
    this.client.on('raw', (packet) => {
      if (!packet.t || !packet.d) return;
      if (
        this.isStreaming &&
        packet.t.startsWith('STREAM_') &&
        packet.d?.stream_key?.includes(this.client.user?.id)
      ) {
        this.client.ws.broadcast({
          op: 22,
          d: {
            stream_key: packet.d.stream_key,
            paused: false,
          },
        });
      }
    });

    // 24/7 VC Auto-Reconnect listener
    this.client.on('voiceStateUpdate', async (oldState, newState) => {
      if (newState.id !== this.client.user?.id) return;
      if (!this.stayInVc || !this.targetChannelId) return;

      // Case 1: Account moved to a different voice channel
      if (newState.channelId && newState.channelId !== this.targetChannelId) {
        console.log(
          `[!] [${this.getNickname()}] Voice state shifted to ${newState.channelId}. Re-enforcing target VC: ${this.targetChannelId}`
        );
        setTimeout(async () => {
          if (!this.stayInVc || !this.targetChannelId) return;
          try {
            const channel = await this.client.channels.fetch(this.targetChannelId).catch(() => null);
            if (channel) {
              await this.joinVoiceChannel(channel, {
                selfMute: this.isSelfMute,
                selfDeaf: this.isSelfDeaf,
                selfVideo: this.isCameraOn,
              });
              console.log(
                `[+] [${this.getNickname()}] Restored 24/7 presence in VC: ${channel.name || channel.id}`
              );
            }
          } catch (err) {
            console.error(`[-] [${this.getNickname()}] Reconnect error:`, err.message);
          }
        }, 800);
        return;
      }

      // Case 2: Kicked or disconnected from VC
      if (!newState.channelId) {
        if (this.isStreaming) this.isStreaming = false;
        if (this.isReconnecting) return;
        this.isReconnecting = true;

        console.log(`[!] [${this.getNickname()}] Disconnected from VC. Auto-rejoining in 1s...`);
        setTimeout(async () => {
          if (!this.stayInVc || !this.targetChannelId) {
            this.isReconnecting = false;
            return;
          }
          try {
            const channel = await this.client.channels.fetch(this.targetChannelId).catch(() => null);
            if (channel) {
              await this.joinVoiceChannel(channel, {
                selfMute: this.isSelfMute,
                selfDeaf: this.isSelfDeaf,
                selfVideo: this.isCameraOn,
              });
              console.log(
                `[+] [${this.getNickname()}] Auto-reconnected to VC: ${channel.name || channel.id}`
              );
            }
          } catch (err) {
            console.error(`[-] [${this.getNickname()}] Reconnect attempt failed:`, err.message);
          } finally {
            this.isReconnecting = false;
          }
        }, 1000);
      }
    });
  }

  login() {
    this.status = 'connecting';
    this.client.login(this.token).catch((err) => {
      this.status = 'error';
      this.errorMessage = err.message;
      console.error(`[-] [Account ${this.id}] Login failed:`, err.message);
    });
  }

  async joinVoiceChannel(channel, options = {}) {
    const guildId = channel.guild?.id || null;
    const channelId = channel.id;

    if (options.selfMute !== undefined) this.isSelfMute = Boolean(options.selfMute);
    if (options.selfDeaf !== undefined) this.isSelfDeaf = Boolean(options.selfDeaf);
    if (options.selfVideo !== undefined) this.isCameraOn = Boolean(options.selfVideo);

    this.stayInVc = true;
    this.targetChannelId = channelId;
    this.targetGuildId = guildId;
    this.channelName = channel.name || channelId;

    this.client.ws.broadcast({
      op: 4,
      d: {
        guild_id: guildId,
        channel_id: channelId,
        self_mute: this.isSelfMute,
        self_deaf: this.isSelfDeaf,
        self_video: this.isCameraOn,
        flags: 2,
      },
    });
  }

  async updateVoiceState(mute, deaf, video = this.isCameraOn) {
    this.isSelfMute = Boolean(mute);
    this.isSelfDeaf = Boolean(deaf);
    this.isCameraOn = Boolean(video);

    let channelId = this.targetChannelId;
    let guildId = this.targetGuildId;

    if (!channelId && this.client.guilds?.cache) {
      for (const guild of this.client.guilds.cache.values()) {
        if (guild.members.me?.voice?.channelId) {
          channelId = guild.members.me.voice.channelId;
          guildId = guild.id;
          this.targetChannelId = channelId;
          this.targetGuildId = guildId;
          break;
        }
      }
    }

    if (channelId) {
      this.client.ws.broadcast({
        op: 4,
        d: {
          guild_id: guildId || null,
          channel_id: channelId,
          self_mute: this.isSelfMute,
          self_deaf: this.isSelfDeaf,
          self_video: this.isCameraOn,
          flags: 2,
        },
      });
    }

    if (this.client.voice?.connection && typeof this.client.voice.connection.setVideoStatus === 'function') {
      try {
        this.client.voice.connection.setVideoStatus(this.isCameraOn);
      } catch (_) {}
    }
  }

  async leaveVoiceChannel() {
    await this.stopScreenShare();
    this.isCameraOn = false;
    this.stayInVc = false;

    const guildId = this.targetGuildId;
    this.targetChannelId = null;
    this.targetGuildId = null;
    this.channelName = null;

    if (guildId) {
      this.client.ws.broadcast({
        op: 4,
        d: {
          guild_id: guildId,
          channel_id: null,
          self_mute: false,
          self_deaf: false,
          self_video: false,
        },
      });
    }

    if (this.client.guilds?.cache) {
      for (const guild of this.client.guilds.cache.values()) {
        if (guild.members.me?.voice?.channelId) {
          this.client.ws.broadcast({
            op: 4,
            d: {
              guild_id: guild.id,
              channel_id: null,
              self_mute: false,
              self_deaf: false,
              self_video: false,
            },
          });
          guild.members.me.voice.disconnect().catch(() => {});
        }
      }
    }
  }

  async setCameraState(enabled, channel = null) {
    this.isCameraOn = Boolean(enabled);
    let targetChannel = channel?.id || this.targetChannelId;
    let targetGuild = channel?.guild?.id || this.targetGuildId;

    if (!targetChannel && this.client.guilds?.cache) {
      for (const guild of this.client.guilds.cache.values()) {
        if (guild.members.me?.voice?.channelId) {
          targetChannel = guild.members.me.voice.channelId;
          targetGuild = guild.id;
          this.targetChannelId = targetChannel;
          this.targetGuildId = targetGuild;
          break;
        }
      }
    }

    if (targetChannel) {
      this.client.ws.broadcast({
        op: 4,
        d: {
          guild_id: targetGuild || null,
          channel_id: targetChannel,
          self_mute: this.isSelfMute,
          self_deaf: this.isSelfDeaf,
          self_video: this.isCameraOn,
          flags: 2,
        },
      });
    }

    if (this.client.voice?.connection && typeof this.client.voice.connection.setVideoStatus === 'function') {
      try {
        this.client.voice.connection.setVideoStatus(this.isCameraOn);
      } catch (_) {}
    }
  }

  async startScreenShare(channel) {
    const guildId = channel.guild?.id || null;
    const channelId = channel.id;

    this.isStreaming = true;
    this.streamingChannelId = channelId;
    this.streamingGuildId = guildId;

    const userId = this.client.user?.id;
    const streamKey = guildId
      ? `guild:${guildId}:${channelId}:${userId}`
      : `call:${channelId}:${userId}`;

    this.client.ws.broadcast({
      op: 18,
      d: {
        type: guildId ? 'guild' : 'call',
        guild_id: guildId,
        channel_id: channelId,
        preferred_region: null,
      },
    });

    this.client.ws.broadcast({
      op: 22,
      d: {
        stream_key: streamKey,
        paused: false,
      },
    });
  }

  async stopScreenShare() {
    const streamKeys = new Set();
    const userId = this.client.user?.id;
    if (!userId) {
      this.isStreaming = false;
      return;
    }

    if (this.streamingChannelId) {
      streamKeys.add(
        this.streamingGuildId
          ? `guild:${this.streamingGuildId}:${this.streamingChannelId}:${userId}`
          : `call:${this.streamingChannelId}:${userId}`
      );
    }

    if (this.targetChannelId) {
      streamKeys.add(
        this.targetGuildId
          ? `guild:${this.targetGuildId}:${this.targetChannelId}:${userId}`
          : `call:${this.targetChannelId}:${userId}`
      );
    }

    if (this.client.guilds?.cache) {
      for (const guild of this.client.guilds.cache.values()) {
        const me = guild.members.me;
        if (me?.voice?.channelId) {
          streamKeys.add(`guild:${guild.id}:${me.voice.channelId}:${userId}`);
        }
      }
    }

    for (const streamKey of streamKeys) {
      this.client.ws.broadcast({
        op: 22,
        d: { stream_key: streamKey, paused: true },
      });
      this.client.ws.broadcast({
        op: 19,
        d: { stream_key: streamKey },
      });
    }

    this.isStreaming = false;
    this.streamingChannelId = null;
    this.streamingGuildId = null;
  }

  async destroy() {
    this.stayInVc = false;
    try {
      await this.leaveVoiceChannel();
    } catch (_) {}
    try {
      this.client.destroy();
    } catch (_) {}
    this.status = 'offline';
  }
}

// Map of active accounts by internal ID
const accounts = new Map();
let accountIndexCounter = 1;

function addAccount(token) {
  if (accounts.size >= MAX_ACCOUNTS) {
    throw new Error(`Max accounts limit of ${MAX_ACCOUNTS} reached`);
  }
  const id = `acc_${accountIndexCounter++}`;
  const account = new Account(id, token, {
    selfMute: config.selfMute,
    selfDeaf: config.selfDeaf,
  });
  accounts.set(id, account);
  return account;
}

// Initialize accounts from config
for (const token of initialTokens) {
  try {
    addAccount(token);
  } catch (err) {
    console.error('[!] Failed to initialize account:', err.message);
  }
}

// Periodic Watchdog: Checks every 15s to keep all accounts in VC
setInterval(async () => {
  for (const acc of accounts.values()) {
    if (acc.stayInVc && acc.targetChannelId && acc.client.isReady()) {
      let isInTarget = false;
      if (acc.client.guilds?.cache) {
        for (const guild of acc.client.guilds.cache.values()) {
          if (guild.members.me?.voice?.channelId === acc.targetChannelId) {
            isInTarget = true;
            break;
          }
        }
      }
      if (!isInTarget) {
        console.log(`[Watchdog] [${acc.getNickname()}] Missing from target VC #${acc.targetChannelId}. Auto-rejoining...`);
        try {
          const channel = await acc.client.channels.fetch(acc.targetChannelId).catch(() => null);
          if (channel) {
            await acc.joinVoiceChannel(channel, {
              selfMute: acc.isSelfMute,
              selfDeaf: acc.isSelfDeaf,
              selfVideo: acc.isCameraOn,
            });
          }
        } catch (_) {}
      }
    }
  }
}, 15000);

// Global unhandled error protection
process.on('unhandledRejection', (reason) => {
  console.error('[Unhandled Rejection]', reason);
});
process.on('uncaughtException', (err) => {
  console.error('[Uncaught Exception]', err);
});

// Start Web Dashboard
try {
  const { createDashboard } = require('./webDashboard');
  const dashboardPort = config.dashboardPort || 3000;

  createDashboard(
    // State getter
    () => {
      const mem = process.memoryUsage();
      const accList = [];
      let loggedInCount = 0;
      let activeVcCount = 0;

      for (const acc of accounts.values()) {
        const isOnline = acc.status === 'online' && acc.client.isReady();
        if (isOnline) loggedInCount++;
        const inVc = Boolean(acc.stayInVc && acc.targetChannelId);
        if (inVc) activeVcCount++;

        accList.push({
          id: acc.id,
          nickname: acc.getNickname(),
          username: acc.client?.user?.username || 'Unknown',
          discriminator: acc.client?.user?.discriminator || '0000',
          tag: acc.client?.user?.tag || acc.id,
          avatarUrl: acc.client?.user?.displayAvatarURL?.({ format: 'png', size: 64 }) || null,
          status: acc.status,
          errorMessage: acc.errorMessage,
          voice: {
            inVc,
            channelId: acc.targetChannelId,
            channelName: acc.channelName || (acc.targetChannelId ? `#${acc.targetChannelId}` : null),
            isMuted: acc.isSelfMute,
            isDeafened: acc.isSelfDeaf,
            isCamOn: acc.isCameraOn,
            isStreaming: acc.isStreaming,
          },
        });
      }

      return {
        accounts: accList,
        stats: {
          totalAccounts: accounts.size,
          loggedInCount,
          activeVcCount,
          maxAccounts: MAX_ACCOUNTS,
        },
        memory: {
          heapUsedMb: +(mem.heapUsed / 1024 / 1024).toFixed(1),
          heapTotalMb: +(mem.heapTotal / 1024 / 1024).toFixed(1),
          rssMb: +(mem.rss / 1024 / 1024).toFixed(1),
          limitMb: 30,
          cleanedCount,
        },
        uptime: Math.floor(process.uptime()),
      };
    },
    // Action handler
    async (action, data) => {
      if (action === 'cleanRam') {
        const res = cleanMemory('manual');
        return `Cleaned: Heap ${res.afterHeapMb}MB (RSS: ${res.afterRssMb}MB)`;
      }

      if (action === 'addAccount') {
        const token = data.token?.trim();
        if (!token) throw new Error('Token is required');
        if (accounts.size >= MAX_ACCOUNTS) {
          throw new Error(`Maximum limit of ${MAX_ACCOUNTS} accounts reached`);
        }
        for (const acc of accounts.values()) {
          if (acc.token === token) throw new Error('Account with this token is already added');
        }
        const acc = addAccount(token);
        config.tokens = Array.from(accounts.values()).map((a) => a.token).slice(0, MAX_ACCOUNTS);
        saveConfig();
        return `Account added (${acc.id})`;
      }

      if (action === 'removeAccount') {
        const accId = data.accountId;
        const acc = accounts.get(accId);
        if (!acc) throw new Error('Account not found');
        await acc.destroy();
        accounts.delete(accId);
        config.tokens = Array.from(accounts.values()).map((a) => a.token).slice(0, MAX_ACCOUNTS);
        saveConfig();
        cleanMemory('account removed');
        return 'Account removed';
      }

      // Filter target accounts
      let targets = [];
      if (Array.isArray(data.accountIds) && data.accountIds.length > 0) {
        targets = data.accountIds.map((id) => accounts.get(id)).filter(Boolean);
      } else if (data.accountId) {
        const acc = accounts.get(data.accountId);
        if (acc) targets = [acc];
      } else {
        targets = Array.from(accounts.values()).filter((a) => a.status === 'online');
      }

      if (action === 'joinVc') {
        const rawChannelId = data.channelId?.trim();
        if (!rawChannelId) throw new Error('Channel ID is required');
        const channelId = rawChannelId.replace(/[<#>]/g, '');

        if (targets.length === 0) throw new Error('No accounts selected or online');

        const results = [];
        // Stagger joins by 250ms to prevent gateway rate-limits / glitching
        for (let i = 0; i < targets.length; i++) {
          const acc = targets[i];
          if (i > 0) await new Promise((r) => setTimeout(r, 250));
          try {
            const channel = await acc.client.channels.fetch(channelId).catch(() => null);
            if (!channel) {
              results.push(`${acc.getNickname()}: Channel not found`);
              continue;
            }
            await acc.joinVoiceChannel(channel, {
              selfMute: acc.isSelfMute,
              selfDeaf: acc.isSelfDeaf,
            });
            results.push(`${acc.getNickname()}: Joined ${channel.name || channel.id}`);
          } catch (err) {
            results.push(`${acc.getNickname()}: Error (${err.message})`);
          }
        }
        cleanMemory('post joinVc');
        return results.join(', ');
      }

      if (action === 'leaveVc') {
        if (targets.length === 0) throw new Error('No accounts selected');
        for (const acc of targets) {
          await acc.leaveVoiceChannel();
        }
        cleanMemory('post leaveVc');
        return `Left VC for ${targets.length} account(s)`;
      }

      if (action === 'toggleMute') {
        if (targets.length === 0) throw new Error('No accounts selected');
        const newStates = [];
        for (const acc of targets) {
          acc.isSelfMute = !acc.isSelfMute;
          await acc.updateVoiceState(acc.isSelfMute, acc.isSelfDeaf);
          newStates.push(`${acc.getNickname()}: ${acc.isSelfMute ? 'Muted' : 'Unmuted'}`);
        }
        return newStates.join(', ');
      }

      if (action === 'toggleDeaf') {
        if (targets.length === 0) throw new Error('No accounts selected');
        const newStates = [];
        for (const acc of targets) {
          acc.isSelfDeaf = !acc.isSelfDeaf;
          await acc.updateVoiceState(acc.isSelfMute, acc.isSelfDeaf);
          newStates.push(`${acc.getNickname()}: ${acc.isSelfDeaf ? 'Deafened' : 'Undeafened'}`);
        }
        return newStates.join(', ');
      }

      if (action === 'toggleCam') {
        if (targets.length === 0) throw new Error('No accounts selected');
        const newStates = [];
        for (const acc of targets) {
          acc.isCameraOn = !acc.isCameraOn;
          await acc.setCameraState(acc.isCameraOn);
          newStates.push(`${acc.getNickname()}: ${acc.isCameraOn ? 'Cam On' : 'Cam Off'}`);
        }
        return newStates.join(', ');
      }

      if (action === 'toggleStream') {
        if (targets.length === 0) throw new Error('No accounts selected');
        const newStates = [];
        for (const acc of targets) {
          if (acc.isStreaming) {
            await acc.stopScreenShare();
            newStates.push(`${acc.getNickname()}: Stream Stopped`);
          } else {
            if (!acc.targetChannelId) {
              newStates.push(`${acc.getNickname()}: Not in VC`);
              continue;
            }
            const vc =
              acc.client.channels.cache.get(acc.targetChannelId) ||
              (await acc.client.channels.fetch(acc.targetChannelId).catch(() => null));
            if (vc) {
              await acc.startScreenShare(vc);
              newStates.push(`${acc.getNickname()}: Stream Started`);
            } else {
              newStates.push(`${acc.getNickname()}: VC not found`);
            }
          }
        }
        return newStates.join(', ');
      }

      throw new Error(`Unknown action: ${action}`);
    },
    dashboardPort
  );
} catch (err) {
  console.error('[!] Failed to launch mobile web dashboard:', err.message);
}

// Clean graceful shutdown
async function gracefulShutdown(signal) {
  console.log(`\n[-] Received ${signal}. Disconnecting all accounts cleanly...`);
  for (const acc of accounts.values()) {
    await acc.destroy();
  }
  console.log('[-] All accounts disconnected. Process exiting.');
  process.exit(0);
}

process.on('SIGINT', () => gracefulShutdown('SIGINT'));
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
