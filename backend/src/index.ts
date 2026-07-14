import 'dotenv/config';
import express from 'express';
import http from 'http';
import { Server, Socket } from 'socket.io';
import cors from 'cors';
import crypto from 'crypto';
import jwt from 'jsonwebtoken';
import { dbService } from './db';

const app = express();
const server = http.createServer(app);

// Enable CORS for dashboard access
app.use(cors());
app.use(express.json());

const PORT = process.env.PORT || 4000;
const ADMIN_PASSCODE = process.env.ADMIN_PASSCODE || 'LockItSecret';
const JWT_SECRET = process.env.JWT_SECRET || 'JWT_Secret_Super_Key';

// Setup WebSockets
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  }
});

// Helper for generating an 8-character pairing key (omitting ambiguous characters)
function generatePairingKey(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let key = '';
  for (let i = 0; i < 8; i++) {
    key += chars.charAt(crypto.randomInt(0, chars.length));
  }
  return key;
}

// Helper for generating secure random tokens
function generateToken(): string {
  return crypto.randomBytes(32).toString('hex');
}

// ----------------------------------------------------
// Express Middlewares
// ----------------------------------------------------
const authenticateAdmin = (req: express.Request, res: express.Response, next: express.NextFunction) => {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Unauthorized: Missing admin token.' });
  }

  const token = authHeader.split(' ')[1];
  try {
    const decoded = jwt.verify(token, JWT_SECRET);
    (req as any).admin = decoded;
    next();
  } catch (error) {
    return res.status(401).json({ error: 'Unauthorized: Invalid admin token.' });
  }
};

// ----------------------------------------------------
// Express APIs
// ----------------------------------------------------

// Admin authentication
app.post('/api/admin/login', (req, res) => {
  const { passcode } = req.body;
  if (passcode === ADMIN_PASSCODE) {
    const token = jwt.sign({ role: 'admin' }, JWT_SECRET, { expiresIn: '7d' });
    return res.json({ success: true, token });
  }
  return res.status(401).json({ error: 'Invalid admin passcode.' });
});

// Verify admin session validity
app.get('/api/admin/verify', authenticateAdmin, (req, res) => {
  res.json({ success: true });
});

// Android request to generate a pairing key
app.post('/api/device/pair-request', async (req, res) => {
  const { deviceId, deviceName } = req.body;
  if (!deviceId || !deviceName) {
    return res.status(400).json({ error: 'Missing deviceId or deviceName.' });
  }

  const key = generatePairingKey();
  const expiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString(); // 5 minutes expiration

  try {
    // Delete any active pairing request from this device
    await dbService.deletePairingKeysByDeviceId(deviceId);
    
    // Save new key
    await dbService.insertPairingKey(key, deviceId, deviceName, expiresAt);

    console.log(`[Pairing] Generated key ${key} for device '${deviceName}' (expires at ${expiresAt})`);
    return res.json({ pairingKey: key, expiresInSeconds: 300 });
  } catch (error) {
    console.error('Error in pairing request:', error);
    return res.status(500).json({ error: 'Database error generating key.' });
  }
});

// Android check pairing status and retrieve token
app.get('/api/device/pair-status', async (req, res) => {
  const { deviceId } = req.query as { deviceId?: string };
  if (!deviceId) {
    return res.status(400).json({ error: 'Missing deviceId.' });
  }

  try {
    const device = await dbService.getDevice(deviceId);
    if (device) {
      return res.json({ paired: true, token: device.token });
    }
    return res.json({ paired: false });
  } catch (error) {
    return res.status(500).json({ error: 'Database verification failed.' });
  }
});

// Admin verify key and perform pairing
app.post('/api/admin/pair', authenticateAdmin, async (req, res) => {
  const { pairingKey } = req.body;
  if (!pairingKey) {
    return res.status(400).json({ error: 'Missing pairingKey.' });
  }

  const normalizedKey = pairingKey.toUpperCase().trim();

  try {
    const request = await dbService.getPairingKey(normalizedKey);

    if (!request) {
      return res.status(400).json({ error: 'Invalid pairing key.' });
    }

    const now = new Date().toISOString();
    if (request.expires_at < now) {
      // Clean up expired key
      await dbService.deletePairingKey(normalizedKey);
      return res.status(400).json({ error: 'Pairing key has expired (5-minute limit).' });
    }

    // Generate permanent token for the device
    const deviceToken = generateToken();

    // Upsert device in the database
    await dbService.upsertDevice(request.device_id, request.device_name, deviceToken);

    // Remove the pairing key
    await dbService.deletePairingKey(normalizedKey);

    console.log(`[Pairing] Device successfully paired: ${request.device_name} (${request.device_id})`);

    // Broadcast update to all admins
    io.to('admins').emit('devices_changed');

    return res.json({
      success: true,
      deviceId: request.device_id,
      deviceName: request.device_name,
      deviceToken
    });
  } catch (error) {
    console.error('Error during pairing process:', error);
    return res.status(500).json({ error: 'Internal pairing failure.' });
  }
});

// Get paired devices list
app.get('/api/admin/devices', authenticateAdmin, async (req, res) => {
  try {
    const devices = await dbService.getDevices();
    res.json(devices);
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch devices.' });
  }
});

// Get command logs
app.get('/api/admin/history/:deviceId', authenticateAdmin, async (req, res) => {
  const { deviceId } = req.params;
  try {
    const logs = await dbService.getCommandHistory(deviceId);
    res.json(logs);
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch command history.' });
  }
});

// Relay command to device
app.post('/api/admin/command', authenticateAdmin, async (req, res) => {
  const { deviceId, command, payload } = req.body;
  if (!deviceId || !command) {
    return res.status(400).json({ error: 'Missing deviceId or command.' });
  }

  try {
    const device = await dbService.getDevice(deviceId);
    if (!device) {
      return res.status(404).json({ error: 'Device not found.' });
    }

    const commandId = crypto.randomUUID();
    const payloadStr = payload ? JSON.stringify(payload) : null;

    // Check if device is connected to WebSocket
    const isOnline = device.status === 'online';

    // Insert as PENDING or FAILED
    const initialStatus = isOnline ? 'PENDING' : 'FAILED';
    await dbService.insertCommand(commandId, deviceId, command, payloadStr, initialStatus);

    if (!isOnline) {
      return res.status(400).json({ error: 'Device is offline. Command failed.' });
    }

    // Forward command to device WebSocket room
    console.log(`[Command] Sending command '${command}' (${commandId}) to device ${deviceId}`);
    io.to(`device_${deviceId}`).emit('command', {
      id: commandId,
      command,
      payload
    });

    // Notify admins about the new entry
    io.to('admins').emit('history_changed', { deviceId });

    return res.json({ success: true, commandId, status: 'PENDING' });
  } catch (error) {
    console.error('Command dispatch error:', error);
    res.status(500).json({ error: 'Failed to log or send command.' });
  }
});

// Remove/unpair a device
app.delete('/api/admin/devices/:deviceId', authenticateAdmin, async (req, res) => {
  const { deviceId } = req.params;
  try {
    await dbService.deleteDevice(deviceId);
    console.log(`[Unpair] Device ${deviceId} deleted by admin`);

    // Disconnect active socket if exists
    const sockets = await io.in(`device_${deviceId}`).fetchSockets();
    for (const socket of sockets) {
      socket.disconnect();
    }

    io.to('admins').emit('devices_changed');
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: 'Failed to unpair device.' });
  }
});

// ----------------------------------------------------
// WebSocket Server Connection Manager
// ----------------------------------------------------

io.use(async (socket: Socket, next) => {
  const token = socket.handshake.auth.token;
  const role = socket.handshake.auth.role; // 'admin' or 'device'

  if (role === 'admin') {
    // Authenticate admin socket
    if (!token) return next(new Error('Authentication error: Missing admin token'));
    try {
      jwt.verify(token, JWT_SECRET);
      socket.data.role = 'admin';
      return next();
    } catch (err) {
      return next(new Error('Authentication error: Invalid admin token'));
    }
  } else if (role === 'device') {
    // Authenticate device socket
    const deviceId = socket.handshake.auth.deviceId;
    if (!token || !deviceId) {
      return next(new Error('Authentication error: Missing device credentials'));
    }

    try {
      const device = await dbService.getDevice(deviceId);
      if (device && device.token === token) {
        socket.data.role = 'device';
        socket.data.deviceId = deviceId;
        return next();
      }
      return next(new Error('Authentication error: Invalid device credentials'));
    } catch (err) {
      return next(new Error('Database validation error'));
    }
  } else {
    return next(new Error('Authentication error: Unknown role'));
  }
});

io.on('connection', (socket: Socket) => {
  const role = socket.data.role;

  if (role === 'admin') {
    socket.join('admins');
    console.log(`[WS] Admin client connected: ${socket.id}`);
  } else if (role === 'device') {
    const deviceId = socket.data.deviceId;
    socket.join(`device_${deviceId}`);
    console.log(`[WS] Device connected: ${deviceId} (Socket: ${socket.id})`);

    // Update status to online
    dbService.updateDeviceStatus(deviceId, 'online')
      .then(() => {
        io.to('admins').emit('devices_changed');
        io.to('admins').emit('device_status_change', { deviceId, status: 'online' });
      });

    // Handle incoming telemetry updates from Android
    socket.on('telemetry', async (data: { batteryLevel: number; isCharging: boolean; screenStatus: string }) => {
      const { batteryLevel, isCharging, screenStatus } = data;
      console.log(`[Telemetry] Device ${deviceId} - Battery: ${batteryLevel}%, Charging: ${isCharging}, Screen: ${screenStatus}`);

      try {
        await dbService.updateDeviceTelemetry(deviceId, batteryLevel, isCharging, screenStatus || 'UNKNOWN');
        // Broadcast telemetry details to connected admins
        io.to('admins').emit('devices_changed');
      } catch (err) {
        console.error('Error updating telemetry:', err);
      }
    });

    // Handle command feedback acknowledgments from Android app
    socket.on('command_ack', async (data: { commandId: string; status: 'SUCCESS' | 'FAILED'; error?: string }) => {
      const { commandId, status, error } = data;
      console.log(`[Ack] Command ${commandId} acknowledgment: ${status} ${error ? `(Error: ${error})` : ''}`);

      const mappedStatus = status === 'SUCCESS' ? 'EXECUTED' : 'FAILED';
      try {
        await dbService.updateCommandStatus(commandId, mappedStatus);
        io.to('admins').emit('history_changed', { deviceId });
      } catch (err) {
        console.error('Error saving command acknowledgment:', err);
      }
    });

    // Handle disconnection
    socket.on('disconnect', () => {
      console.log(`[WS] Device disconnected: ${deviceId}`);
      dbService.updateDeviceStatus(deviceId, 'offline')
        .then(() => {
          io.to('admins').emit('devices_changed');
          io.to('admins').emit('device_status_change', { deviceId, status: 'offline' });
        });
    });
  }
});

// Run server
dbService.init().then(() => {
  server.listen(PORT, () => {
    console.log(`=================================================`);
    console.log(`Remote Companion Server listening on port ${PORT}`);
    console.log(`Admin passcode is: ${ADMIN_PASSCODE}`);
    console.log(`=================================================`);
  });
});
