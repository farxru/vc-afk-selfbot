const { Client } = require('discord.js-selfbot-v13');
const fs = require('fs');
const path = require('path');

// Load configuration
const configPath = path.join(__dirname, 'config.json');
if (!fs.existsSync(configPath)) {
  console.error('[!] config.json not found. Please create it using the template.');
  process.exit(1);
}

const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));

// Initialize client
const client = new Client({
  checkUpdate: false, // Disables update checks for fast and safe startup
});

// State tracking for VC and screenshare
let stayInVc = false;
let targetChannelId = null;
let targetGuildId = null;
let isReconnecting = false;

// Audio and video state tracking
let isExplicitMute = Boolean(config.selfMute);
let isSelfMute = Boolean(config.selfMute);
let isSelfDeaf = Boolean(config.selfDeaf ?? true);
let isCameraOn = false;

let isStreaming = false;
let streamingChannelId = null;
let streamingGuildId = null;

/**
 * Updates self-mute and self-deafen states via Gateway Voice State Update (Opcode 4).
 */
async function updateVoiceState(mute, deaf, video = isCameraOn) {
  isSelfMute = Boolean(mute);
  isSelfDeaf = Boolean(deaf);
  isCameraOn = Boolean(video);

  let channelId = targetChannelId;
  let guildId = targetGuildId;

  if (!channelId) {
    for (const guild of client.guilds.cache.values()) {
      if (guild.members.me?.voice?.channelId) {
        channelId = guild.members.me.voice.channelId;
        guildId = guild.id;
        targetChannelId = channelId;
        targetGuildId = guildId;
        break;
      }
    }
  }

  if (channelId) {
    client.ws.broadcast({
      op: 4,
      d: {
        guild_id: guildId || null,
        channel_id: channelId,
        self_mute: isSelfMute,
        self_deaf: isSelfDeaf,
        self_video: isCameraOn,
        flags: 2,
      },
    });
  }

  if (client.voice?.connection && typeof client.voice.connection.setVideoStatus === 'function') {
    try {
      client.voice.connection.setVideoStatus(isCameraOn);
    } catch (_) {}
  }
}

/**
 * Join voice channel directly via Gateway Voice State Update (Opcode 4).
 * This provides 100% instant, reliable 24/7 VC presence.
 */
async function joinVoiceChannel(channel, options = {}) {
  const guildId = channel.guild?.id || null;
  const channelId = channel.id;

  if (options.selfMute !== undefined) isSelfMute = Boolean(options.selfMute);
  if (options.selfDeaf !== undefined) isSelfDeaf = Boolean(options.selfDeaf);
  if (options.selfVideo !== undefined) isCameraOn = Boolean(options.selfVideo);

  client.ws.broadcast({
    op: 4,
    d: {
      guild_id: guildId,
      channel_id: channelId,
      self_mute: isSelfMute,
      self_deaf: isSelfDeaf,
      self_video: isCameraOn,
      flags: 2,
    },
  });
}

/**
 * Disconnect cleanly from voice channel.
 */
async function leaveVoiceChannel(guildId) {
  // If streaming, ensure screenshare is stopped first
  await stopScreenShare();
  isCameraOn = false;

  if (guildId) {
    client.ws.broadcast({
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

  for (const guild of client.guilds.cache.values()) {
    if (guild.members.me?.voice?.channelId) {
      client.ws.broadcast({
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

/**
 * Toggles camera (self_video) state via Gateway Opcode 4 and active voice connections.
 */
async function setCameraState(enabled, channel = null) {
  isCameraOn = Boolean(enabled);
  let targetChannel = channel?.id ? channel.id : targetChannelId;
  let targetGuild = channel?.guild?.id ? channel.guild.id : targetGuildId;

  if (!targetChannel) {
    for (const guild of client.guilds.cache.values()) {
      if (guild.members.me?.voice?.channelId) {
        targetChannel = guild.members.me.voice.channelId;
        targetGuild = guild.id;
        targetChannelId = targetChannel;
        targetGuildId = targetGuild;
        break;
      }
    }
  }

  if (targetChannel) {
    client.ws.broadcast({
      op: 4,
      d: {
        guild_id: targetGuild || null,
        channel_id: targetChannel,
        self_mute: isSelfMute,
        self_deaf: isSelfDeaf,
        self_video: isCameraOn,
        flags: 2,
      },
    });
  }

  if (client.voice?.connection && typeof client.voice.connection.setVideoStatus === 'function') {
    try {
      client.voice.connection.setVideoStatus(isCameraOn);
    } catch (_) {}
  }
}

/**
 * Initiates clean blank screenshare via Gateway (Opcode 18 + Opcode 22 unpause).
 */
async function startScreenShare(channel) {
  const guildId = channel.guild?.id || null;
  const channelId = channel.id;

  isStreaming = true;
  streamingChannelId = channelId;
  streamingGuildId = guildId;

  const streamKey = guildId
    ? `guild:${guildId}:${channelId}:${client.user.id}`
    : `call:${channelId}:${client.user.id}`;

  // 1. Send Gateway OP 18 (STREAM_CREATE) to activate Go Live / Screenshare
  client.ws.broadcast({
    op: 18,
    d: {
      type: guildId ? 'guild' : 'call',
      guild_id: guildId,
      channel_id: channelId,
      preferred_region: null,
    },
  });

  // 2. Send Gateway OP 22 (STREAM_SET_PAUSED with paused: false) to unpause the stream
  client.ws.broadcast({
    op: 22,
    d: {
      stream_key: streamKey,
      paused: false,
    },
  });
}

/**
 * Stops any active screenshare unconditionally across all channels and guilds.
 */
async function stopScreenShare(guildHint = null) {
  const streamKeys = new Set();

  if (streamingChannelId) {
    streamKeys.add(
      streamingGuildId
        ? `guild:${streamingGuildId}:${streamingChannelId}:${client.user.id}`
        : `call:${streamingChannelId}:${client.user.id}`
    );
  }

  if (targetChannelId) {
    streamKeys.add(
      targetGuildId
        ? `guild:${targetGuildId}:${targetChannelId}:${client.user.id}`
        : `call:${targetChannelId}:${client.user.id}`
    );
  }

  if (guildHint) {
    const me = guildHint.members.me;
    if (me?.voice?.channelId) {
      streamKeys.add(`guild:${guildHint.id}:${me.voice.channelId}:${client.user.id}`);
    }
  }

  for (const guild of client.guilds.cache.values()) {
    const me = guild.members.me;
    if (me?.voice?.channelId) {
      streamKeys.add(`guild:${guild.id}:${me.voice.channelId}:${client.user.id}`);
    }
  }

  for (const streamKey of streamKeys) {
    // 1. Pause
    client.ws.broadcast({
      op: 22,
      d: {
        stream_key: streamKey,
        paused: true,
      },
    });

    // 2. Delete stream (Opcode 19)
    client.ws.broadcast({
      op: 19,
      d: {
        stream_key: streamKey,
      },
    });
  }

  isStreaming = false;
  streamingChannelId = null;
  streamingGuildId = null;
}

// Keep stream active & unpaused whenever Discord Gateway sends stream events
client.on('raw', (packet) => {
  if (!packet.t || !packet.d) return;

  if (isStreaming && packet.t.startsWith('STREAM_') && packet.d?.stream_key?.includes(client.user?.id)) {
    client.ws.broadcast({
      op: 22,
      d: {
        stream_key: packet.d.stream_key,
        paused: false,
      },
    });
  }
});

client.on('ready', () => {
  console.log('====================================');
  console.log(`[+] Logged in as: ${client.user.tag} (${client.user.id})`);
  console.log(`[+] Owner ID: ${config.ownerId}`);
  console.log(`[+] Prefix: ${config.prefix || '.'}`);
  console.log('[+] Status: Ready and waiting for commands');
  console.log('====================================');
});

client.on('messageCreate', async (message) => {
  if (!message.author) return;

  // STRICT OWNER-ONLY CHECK:
  // Commands only execute for the owner ID or the selfbot user itself.
  // All other users are completely ignored (dead silent).
  const isOwner = message.author.id === config.ownerId || message.author.id === client.user.id;
  if (!isOwner) return;

  const prefix = config.prefix || '.';
  if (!message.content.startsWith(prefix)) return;

  const args = message.content.slice(prefix.length).trim().split(/ +/);
  const command = args.shift()?.toLowerCase();

  // Command: .jvc <channelid>
  if (command === 'jvc') {
    let rawChannelId = args[0];

    if (!rawChannelId) {
      await message.reply('Please provide a voice channel ID: `.jvc <channelid>`').catch(() => {});
      console.log('[-] .jvc command failed: No channel ID provided.');
      return;
    }

    const channelId = rawChannelId.replace(/[<#>]/g, '');

    try {
      const channel = await client.channels.fetch(channelId).catch(() => null);

      if (!channel) {
        await message.reply('Could not find a channel with that ID.').catch(() => {});
        console.log(`[-] Channel not found: ${channelId}`);
        return;
      }

      const isVoiceChannel =
        (typeof channel.isVoice === 'function' && channel.isVoice()) ||
        channel.type === 'GUILD_VOICE' ||
        channel.type === 'GUILD_STAGE_VOICE';

      if (!isVoiceChannel) {
        await message.reply('The specified channel is not a voice channel.').catch(() => {});
        console.log(`[-] Channel ${channelId} is not a voice channel.`);
        return;
      }

      stayInVc = true;
      targetChannelId = channel.id;
      targetGuildId = channel.guild?.id || null;

      await joinVoiceChannel(channel, {
        selfMute: isSelfMute,
        selfDeaf: isSelfDeaf,
      });

      console.log(`[+] Joined VC: ${channel.name} (${channel.id})`);
      await message.reply(`Joined VC: ${channel.name}`).catch(() => {});

    } catch (error) {
      console.error('[-] Error joining VC:', error.message);
      await message.reply(`Failed to join VC: ${error.message}`).catch(() => {});
    }
    return;
  }

  // Command: .lvc
  if (command === 'lvc') {
    // If currently streaming, stop stream first
    await stopScreenShare(message.guild);
    isCameraOn = false;

    stayInVc = false;
    const oldGuildId = targetGuildId;
    targetChannelId = null;
    targetGuildId = null;

    try {
      await leaveVoiceChannel(oldGuildId);
    } catch (err) {
      console.error('[-] Error leaving VC:', err.message);
    }

    console.log('[-] Left VC');
    await message.reply('Left VC').catch(() => {});
    return;
  }

  // Command: .mute
  if (command === 'mute') {
    const currentChannelId = targetChannelId || message.guild?.members.me?.voice?.channelId;
    if (!currentChannelId) {
      await message.reply('Bot is not in a voice channel.').catch(() => {});
      console.log('[-] .mute failed: Bot not in a voice channel.');
      return;
    }

    if (!targetChannelId && message.guild?.members.me?.voice?.channelId) {
      targetChannelId = message.guild.members.me.voice.channelId;
      targetGuildId = message.guild.id;
    }

    isExplicitMute = true;
    await updateVoiceState(true, isSelfDeaf);
    console.log('[+] Muted in voice channel');
    await message.reply('Muted').catch(() => {});
    return;
  }

  // Command: .unmute
  if (command === 'unmute') {
    const currentChannelId = targetChannelId || message.guild?.members.me?.voice?.channelId;
    if (!currentChannelId) {
      await message.reply('Bot is not in a voice channel.').catch(() => {});
      console.log('[-] .unmute failed: Bot not in a voice channel.');
      return;
    }

    if (!targetChannelId && message.guild?.members.me?.voice?.channelId) {
      targetChannelId = message.guild.members.me.voice.channelId;
      targetGuildId = message.guild.id;
    }

    isExplicitMute = false;
    // In Discord, unmuting also undeafens if deafened
    await updateVoiceState(false, false);
    console.log('[+] Unmuted in voice channel');
    await message.reply('Unmuted').catch(() => {});
    return;
  }

  // Command: .deaf / .deafen
  if (command === 'deaf' || command === 'deafen') {
    const currentChannelId = targetChannelId || message.guild?.members.me?.voice?.channelId;
    if (!currentChannelId) {
      await message.reply('Bot is not in a voice channel.').catch(() => {});
      console.log('[-] .deaf failed: Bot not in a voice channel.');
      return;
    }

    if (!targetChannelId && message.guild?.members.me?.voice?.channelId) {
      targetChannelId = message.guild.members.me.voice.channelId;
      targetGuildId = message.guild.id;
    }

    // Deafening on Discord requires self_mute: true
    await updateVoiceState(true, true);
    console.log('[+] Deafened in voice channel');
    await message.reply('Deafened').catch(() => {});
    return;
  }

  // Command: .undeaf / .undeafen
  if (command === 'undeaf' || command === 'undeafen') {
    const currentChannelId = targetChannelId || message.guild?.members.me?.voice?.channelId;
    if (!currentChannelId) {
      await message.reply('Bot is not in a voice channel.').catch(() => {});
      console.log('[-] .undeaf failed: Bot not in a voice channel.');
      return;
    }

    if (!targetChannelId && message.guild?.members.me?.voice?.channelId) {
      targetChannelId = message.guild.members.me.voice.channelId;
      targetGuildId = message.guild.id;
    }

    // Undeafens, and restores unmute unless the user explicitly ran .mute
    await updateVoiceState(isExplicitMute, false);
    console.log('[+] Undeafened in voice channel');
    await message.reply('Undeafened').catch(() => {});
    return;
  }

  // Command: .ss (Start blank screenshare with VC check)
  if (command === 'ss') {
    // 1. Check if user/bot is connected to a voice channel
    let voiceChannel = null;

    if (targetChannelId) {
      voiceChannel =
        client.channels.cache.get(targetChannelId) ||
        (await client.channels.fetch(targetChannelId).catch(() => null));
    }

    // If bot isn't in a VC yet, check if the owner is currently sitting in a VC
    if (!voiceChannel && message.member?.voice?.channel) {
      voiceChannel = message.member.voice.channel;
      stayInVc = true;
      targetChannelId = voiceChannel.id;
      targetGuildId = voiceChannel.guild?.id || null;
      await joinVoiceChannel(voiceChannel, {
        selfMute: isSelfMute,
        selfDeaf: isSelfDeaf,
      });
      console.log(`[+] Auto-joined owner's VC: ${voiceChannel.name} (${voiceChannel.id})`);
    }

    // If neither the bot nor the user is in a voice channel
    if (!voiceChannel) {
      await message.reply('Not connected to a voice channel! Please join a voice channel or use `.jvc <channelid>` first.').catch(() => {});
      console.log('[-] .ss failed: User/Bot is not in any voice channel.');
      return;
    }

    try {
      await startScreenShare(voiceChannel);
      console.log(`[+] Started screenshare in ${voiceChannel.name}`);
      await message.reply(`Started screenshare in ${voiceChannel.name}`).catch(() => {});
    } catch (err) {
      console.error('[-] Error starting screenshare:', err.message);
      await message.reply(`Failed to start screenshare: ${err.message}`).catch(() => {});
    }
    return;
  }

  // Command: .ssoff (Stop screenshare)
  if (command === 'ssoff') {
    try {
      await stopScreenShare(message.guild);
      console.log('[-] Stopped screenshare');
      await message.reply('Stopped screenshare').catch(() => {});
    } catch (err) {
      console.error('[-] Error stopping screenshare:', err.message);
      await message.reply(`Failed to stop screenshare: ${err.message}`).catch(() => {});
    }
    return;
  }

  // Command: .cam (Open camera / turn on webcam in VC)
  if (command === 'cam') {
    // 1. Check if user/bot is connected to a voice channel
    let voiceChannel = null;

    if (targetChannelId) {
      voiceChannel =
        client.channels.cache.get(targetChannelId) ||
        (await client.channels.fetch(targetChannelId).catch(() => null));
    }

    // If bot isn't in a VC yet, check if the owner is currently sitting in a VC
    if (!voiceChannel && message.member?.voice?.channel) {
      voiceChannel = message.member.voice.channel;
      stayInVc = true;
      targetChannelId = voiceChannel.id;
      targetGuildId = voiceChannel.guild?.id || null;
      await joinVoiceChannel(voiceChannel, {
        selfMute: isSelfMute,
        selfDeaf: isSelfDeaf,
        selfVideo: true,
      });
      console.log(`[+] Auto-joined owner's VC: ${voiceChannel.name} (${voiceChannel.id})`);
    }

    // If neither the bot nor the user is in a voice channel
    if (!voiceChannel) {
      await message.reply('Not connected to a voice channel! Please join a voice channel or use `.jvc <channelid>` first.').catch(() => {});
      console.log('[-] .cam failed: User/Bot is not in any voice channel.');
      return;
    }

    try {
      await setCameraState(true, voiceChannel);
      console.log(`[+] Opened camera in ${voiceChannel.name}`);
      await message.reply(`Opened camera in ${voiceChannel.name}`).catch(() => {});
    } catch (err) {
      console.error('[-] Error opening camera:', err.message);
      await message.reply(`Failed to open camera: ${err.message}`).catch(() => {});
    }
    return;
  }

  // Command: .camoff (Turn off camera in VC)
  if (command === 'camoff') {
    const currentChannelId = targetChannelId || message.guild?.members.me?.voice?.channelId;
    if (!currentChannelId) {
      await message.reply('Bot is not in a voice channel.').catch(() => {});
      console.log('[-] .camoff failed: Bot not in a voice channel.');
      return;
    }

    try {
      await setCameraState(false);
      console.log('[-] Turned off camera');
      await message.reply('Turned off camera').catch(() => {});
    } catch (err) {
      console.error('[-] Error turning off camera:', err.message);
      await message.reply(`Failed to turn off camera: ${err.message}`).catch(() => {});
    }
    return;
  }
});

// Auto-reconnect listener: Keeps the bot in target VC 24/7 even on kick, disconnect, or main Discord conflict
client.on('voiceStateUpdate', async (oldState, newState) => {
  if (newState.id !== client.user?.id) return;

  if (!stayInVc || !targetChannelId) return;

  // Case 1: Account moved to a different voice channel (e.g. main Discord joined another VC)
  if (newState.channelId && newState.channelId !== targetChannelId) {
    console.log(`[!] Account voice state shifted to ${newState.channelId}. Re-enforcing target VC: ${targetChannelId}`);
    setTimeout(async () => {
      if (!stayInVc || !targetChannelId) return;
      try {
        const channel = await client.channels.fetch(targetChannelId).catch(() => null);
        if (channel) {
          await joinVoiceChannel(channel, {
            selfMute: isSelfMute,
            selfDeaf: isSelfDeaf,
            selfVideo: isCameraOn,
          });
          console.log(`[+] Restored 24/7 presence in VC: ${channel.name} (${channel.id})`);
        }
      } catch (err) {
        console.error('[-] Reconnect error:', err.message);
      }
    }, 800);
    return;
  }

  // Case 2: Kicked or disconnected from VC
  if (!newState.channelId) {
    if (isStreaming) {
      isStreaming = false;
    }

    if (isReconnecting) return;
    isReconnecting = true;

    console.log('[!] Kicked or disconnected from VC. Auto-rejoining in 1 second...');

    setTimeout(async () => {
      if (!stayInVc || !targetChannelId) {
        isReconnecting = false;
        return;
      }

      try {
        const channel = await client.channels.fetch(targetChannelId).catch(() => null);
        if (channel) {
          await joinVoiceChannel(channel, {
            selfMute: isSelfMute,
            selfDeaf: isSelfDeaf,
            selfVideo: isCameraOn,
          });
          console.log(`[+] Auto-reconnected to VC: ${channel.name} (${channel.id})`);
        }
      } catch (err) {
        console.error('[-] Reconnect attempt failed:', err.message);
      } finally {
        isReconnecting = false;
      }
    }, 1000);
  }
});

// Periodic Watchdog: Verifies target VC presence every 15 seconds
setInterval(async () => {
  if (stayInVc && targetChannelId && client.isReady()) {
    let isInTargetVc = false;
    for (const guild of client.guilds.cache.values()) {
      if (guild.members.me?.voice?.channelId === targetChannelId) {
        isInTargetVc = true;
        break;
      }
    }
    if (!isInTargetVc) {
      console.log(`[Watchdog] Missing from target VC #${targetChannelId}. Auto-rejoining...`);
      try {
        const channel = await client.channels.fetch(targetChannelId).catch(() => null);
        if (channel) {
          await joinVoiceChannel(channel, {
            selfMute: isSelfMute,
            selfDeaf: isSelfDeaf,
            selfVideo: isCameraOn,
          });
        }
      } catch (_) {}
    }
  }
}, 15000);

// Global error handlers to keep the bot alive and prevent unexpected crashes
process.on('unhandledRejection', (reason) => {
  console.error('[Unhandled Rejection]', reason);
});

process.on('uncaughtException', (err) => {
  console.error('[Uncaught Exception]', err);
});

// Validate configuration and login
if (!config.token || config.token.includes('PASTE_YOUR_USER_TOKEN')) {
  console.error('[!] Please set your valid user token in config.json before starting the bot.');
  process.exit(1);
}

if (!config.ownerId || config.ownerId.includes('PASTE_YOUR_OWNER')) {
  console.warn('[!] Warning: ownerId in config.json is still set to placeholder. Please update it with your Discord user ID.');
}

// Initialize Mobile Web App / PWA Dashboard
try {
  const { createDashboard } = require('./webDashboard');
  const dashboardPort = config.dashboardPort || 3000;
  createDashboard(
    () => ({
      online: Boolean(client.isReady()),
      user: client.user ? `${client.user.username}#${client.user.discriminator || '0000'}` : null,
      channel: targetChannelId ? (client.channels.cache.get(targetChannelId)?.name || targetChannelId) : null,
      isMuted: isSelfMute,
      isDeafened: isSelfDeaf,
      isCamOn: isCameraOn,
      isStreaming: isStreaming,
      uptime: Math.floor(process.uptime()),
    }),
    async (action, data) => {
      if (action === 'joinVc' && data.channelId) {
        const ch = await client.channels.fetch(data.channelId).catch(() => null);
        if (ch) {
          stayInVc = true;
          targetChannelId = ch.id;
          targetGuildId = ch.guild?.id || null;
          await joinVoiceChannel(ch, { selfMute: isSelfMute, selfDeaf: isSelfDeaf });
          return `Joined ${ch.name}`;
        }
        throw new Error('Channel not found');
      }
      if (action === 'leaveVc') {
        stayInVc = false;
        await leaveVoiceChannel(targetGuildId);
        targetChannelId = null;
        targetGuildId = null;
        return 'Left VC';
      }
      if (action === 'toggleMute') {
        isSelfMute = !isSelfMute;
        await updateVoiceState(isSelfMute, isSelfDeaf);
        return isSelfMute ? 'Muted' : 'Unmuted';
      }
      if (action === 'toggleDeaf') {
        isSelfDeaf = !isSelfDeaf;
        await updateVoiceState(isSelfMute, isSelfDeaf);
        return isSelfDeaf ? 'Deafened' : 'Undeafened';
      }
      if (action === 'toggleCam') {
        isCameraOn = !isCameraOn;
        await setCameraState(isCameraOn);
        return isCameraOn ? 'Camera On' : 'Camera Off';
      }
      if (action === 'toggleStream') {
        if (isStreaming) {
          await stopScreenShare();
          return 'Stopped stream';
        } else {
          let vc = targetChannelId ? client.channels.cache.get(targetChannelId) : null;
          if (vc) {
            await startScreenShare(vc);
            return 'Started stream';
          }
          throw new Error('Not in VC');
        }
      }
    },
    dashboardPort
  );
} catch (err) {
  console.error('[!] Failed to launch mobile web dashboard:', err.message);
}

// Graceful shutdown handling to leave VC cleanly
async function gracefulShutdown(signal) {
  console.log(`\n[-] Received ${signal}. Disconnecting from voice channels...`);
  stayInVc = false;
  try {
    await leaveVoiceChannel();
  } catch (_) {}
  try {
    client.destroy();
  } catch (_) {}
  console.log('[-] Bot stopped completely.');
  process.exit(0);
}

process.on('SIGINT', () => gracefulShutdown('SIGINT'));
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));

startLogin();

